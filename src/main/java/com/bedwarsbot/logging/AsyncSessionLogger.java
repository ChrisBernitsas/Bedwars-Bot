package com.bedwarsbot.logging;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class AsyncSessionLogger implements AutoCloseable {
    public static final int DEFAULT_QUEUE_CAPACITY = 1024;

    private static final long CLOSE_TIMEOUT_SECONDS = 5L;

    private final String sessionId;
    private final Path logFile;
    private final int queueCapacity;
    private final ArrayBlockingQueue<LogRecord> queue;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicInteger inFlightProducers = new AtomicInteger();
    private final AtomicLong nextSequence = new AtomicLong();
    private final AtomicLong droppedRecords = new AtomicLong();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final JsonLineEncoder encoder = new JsonLineEncoder();
    private final Thread writerThread;

    private volatile Throwable failure;

    public AsyncSessionLogger(Path logDirectory) {
        this(logDirectory, DEFAULT_QUEUE_CAPACITY, UUID.randomUUID().toString());
    }

    public AsyncSessionLogger(Path logDirectory, int queueCapacity, String sessionId) {
        if (logDirectory == null) {
            throw new IllegalArgumentException("logDirectory must not be null");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("sessionId contains unsafe filename characters");
        }

        this.sessionId = sessionId;
        this.queueCapacity = queueCapacity;
        this.queue = new ArrayBlockingQueue<LogRecord>(queueCapacity);
        this.logFile = logDirectory.resolve("session-" + sessionId + ".jsonl");
        this.writerThread = new Thread(new WriterLoop(logDirectory), "bedwarsbot-log-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    public boolean tryLog(
        String component,
        String eventType,
        long clientTick,
        Long worldTick,
        Map<String, String> details
    ) {
        inFlightProducers.incrementAndGet();
        try {
            if (!accepting.get()) {
                return false;
            }
            return offerRecord(createRecord(component, eventType, clientTick, worldTick, details));
        } finally {
            inFlightProducers.decrementAndGet();
        }
    }

    public boolean tryLog(String component, String eventType, long clientTick, Long worldTick) {
        return tryLog(component, eventType, clientTick, worldTick, Collections.<String, String>emptyMap());
    }

    private LogRecord createRecord(
        String component,
        String eventType,
        long clientTick,
        Long worldTick,
        Map<String, String> details
    ) {
        return new LogRecord(
            sessionId,
            nextSequence.getAndIncrement(),
            clientTick,
            worldTick,
            System.nanoTime(),
            Instant.now().toString(),
            Thread.currentThread().getName(),
            component,
            eventType,
            details
        );
    }

    private boolean offerRecord(LogRecord record) {
        if (queue.offer(record)) {
            return true;
        }
        droppedRecords.incrementAndGet();
        return false;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Path getLogFile() {
        return logFile;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public int getQueueDepth() {
        return queue.size();
    }

    public long getDroppedRecords() {
        return droppedRecords.get();
    }

    public String getFailureMessage() {
        Throwable currentFailure = failure;
        if (currentFailure == null) {
            return null;
        }
        String message = currentFailure.getMessage();
        return message == null
            ? currentFailure.getClass().getSimpleName()
            : currentFailure.getClass().getSimpleName() + ": " + message;
    }

    @Override
    public void close() {
        inFlightProducers.incrementAndGet();
        try {
            if (accepting.compareAndSet(true, false)) {
                Map<String, String> details = new LinkedHashMap<String, String>();
                details.put("dropped_records", Long.toString(droppedRecords.get()));
                offerRecord(createRecord("session", "session_end", -1L, null, details));
            }
        } finally {
            inFlightProducers.decrementAndGet();
        }

        try {
            if (!stopped.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) && failure == null) {
                failure = new IOException("log writer did not stop within timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (failure == null) {
                failure = interrupted;
            }
        }
    }

    private final class WriterLoop implements Runnable {
        private final Path logDirectory;

        private WriterLoop(Path logDirectory) {
            this.logDirectory = logDirectory;
        }

        @Override
        public void run() {
            BufferedWriter writer = null;
            try {
                Files.createDirectories(logDirectory);
                writer = Files.newBufferedWriter(
                    logFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                );

                while (accepting.get() || inFlightProducers.get() > 0 || !queue.isEmpty()) {
                    LogRecord record = queue.poll(100L, TimeUnit.MILLISECONDS);
                    if (record != null) {
                        writer.write(encoder.encode(record));
                        writer.newLine();
                    }
                    if (queue.isEmpty()) {
                        writer.flush();
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = interrupted;
            } catch (IOException ioFailure) {
                failure = ioFailure;
            } catch (RuntimeException runtimeFailure) {
                failure = runtimeFailure;
            } finally {
                accepting.set(false);
                if (writer != null) {
                    try {
                        writer.flush();
                        writer.close();
                    } catch (IOException closeFailure) {
                        if (failure == null) {
                            failure = closeFailure;
                        }
                    }
                }
                stopped.countDown();
            }
        }
    }
}
