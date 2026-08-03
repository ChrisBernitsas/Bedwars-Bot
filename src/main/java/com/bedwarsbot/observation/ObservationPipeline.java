package com.bedwarsbot.observation;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.bedwarsbot.logging.AsyncSessionLogger;

public final class ObservationPipeline implements AutoCloseable {
    public static final int DEFAULT_QUEUE_CAPACITY = 4096;

    private static final long CLOSE_TIMEOUT_SECONDS = 5L;

    private final AsyncSessionLogger sessionLogger;
    private final BoundedObservationQueue queue;
    private final SparseBlockOverlay overlay = new SparseBlockOverlay();
    private final ObservationEventSerializer serializer = new ObservationEventSerializer();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicLong processedEvents = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong lastProcessingNanos = new AtomicLong();
    private final AtomicLong totalProcessingNanos = new AtomicLong();
    private final AtomicLong maxProcessingNanos = new AtomicLong();
    private final AtomicReference<SparseBlockOverlay.Snapshot> overlaySnapshot;
    private final AtomicReference<ObservationHudSnapshot> hudSnapshot =
        new AtomicReference<ObservationHudSnapshot>();
    private final AtomicReference<String> failureMessage = new AtomicReference<String>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Object captureLock = new Object();
    private final Thread workerThread;

    public ObservationPipeline(AsyncSessionLogger sessionLogger) {
        this(sessionLogger, DEFAULT_QUEUE_CAPACITY);
    }

    public ObservationPipeline(AsyncSessionLogger sessionLogger, int queueCapacity) {
        if (sessionLogger == null) {
            throw new IllegalArgumentException("sessionLogger must not be null");
        }
        this.sessionLogger = sessionLogger;
        this.queue = new BoundedObservationQueue(queueCapacity);
        this.overlaySnapshot = new AtomicReference<SparseBlockOverlay.Snapshot>(overlay.snapshot());
        publishHudSnapshot();
        this.workerThread = new Thread(new WorkerLoop(), "bedwarsbot-observation-worker");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    public boolean tryCapture(ObservationEvent event) {
        boolean accepted;
        synchronized (captureLock) {
            if (!accepting.get()) {
                return false;
            }
            accepted = queue.offer(event);
        }
        publishHudSnapshot();
        return accepted;
    }

    public void recordCaptureFailure(RuntimeException failure) {
        failureCount.incrementAndGet();
        failureMessage.set(describeFailure(failure));
        publishHudSnapshot();
    }

    public AtomicReference<ObservationHudSnapshot> getHudSnapshotReference() {
        return hudSnapshot;
    }

    public SparseBlockOverlay.OverlayValue getOverlayValue(BlockPosition position) {
        return overlay.lookup(position);
    }

    @Override
    public void close() {
        synchronized (captureLock) {
            accepting.set(false);
        }
        try {
            if (!stopped.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                recordPipelineFailure(new IOException("observation worker did not stop within timeout"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            recordPipelineFailure(interrupted);
        }
    }

    private void process(ObservationEvent event) {
        long startNanos = System.nanoTime();
        try {
            SparseBlockOverlay.ApplyResult result = overlay.apply(event);
            SparseBlockOverlay.Snapshot snapshot = overlay.snapshot();
            overlaySnapshot.set(snapshot);
            sessionLogger.tryLog(
                "observation",
                serializer.observationEventType(event),
                event.getClientTick(),
                event.getWorldTick(),
                serializer.observationDetails(event)
            );
            sessionLogger.tryLog(
                "block_overlay",
                serializer.overlayEventType(result),
                event.getClientTick(),
                event.getWorldTick(),
                serializer.overlayDetails(result, snapshot)
            );
        } catch (RuntimeException failure) {
            recordPipelineFailure(failure);
            Map<String, String> details = new LinkedHashMap<String, String>();
            details.put("exception", failure.getClass().getName());
            details.put("message", failure.getMessage() == null ? "" : failure.getMessage());
            details.put("observation_sequence", Long.toString(event.getSequence()));
            details.put("observation_schema_version", Integer.toString(ObservationEvent.SCHEMA_VERSION));
            sessionLogger.tryLog(
                "observation",
                "observation_processing_failure",
                event.getClientTick(),
                event.getWorldTick(),
                details
            );
        } finally {
            long duration = System.nanoTime() - startNanos;
            lastProcessingNanos.set(duration);
            totalProcessingNanos.addAndGet(duration);
            processedEvents.incrementAndGet();
            updateMaximum(duration);
            publishHudSnapshot();
        }
    }

    private void recordPipelineFailure(Throwable failure) {
        failureCount.incrementAndGet();
        failureMessage.set(describeFailure(failure));
        publishHudSnapshot();
    }

    private void updateMaximum(long candidate) {
        long current = maxProcessingNanos.get();
        while (candidate > current && !maxProcessingNanos.compareAndSet(current, candidate)) {
            current = maxProcessingNanos.get();
        }
    }

    private void publishHudSnapshot() {
        long processed = processedEvents.get();
        long average = processed == 0L ? 0L : totalProcessingNanos.get() / processed;
        hudSnapshot.set(new ObservationHudSnapshot(
            overlaySnapshot.get(),
            queue.getDepth(),
            queue.getCapacity(),
            queue.getAcceptedEvents(),
            queue.getDroppedEvents(),
            processed,
            failureCount.get(),
            lastProcessingNanos.get(),
            average,
            maxProcessingNanos.get(),
            failureMessage.get()
        ));
    }

    private static String describeFailure(Throwable failure) {
        if (failure == null) {
            return "unknown observation failure";
        }
        String message = failure.getMessage();
        return message == null
            ? failure.getClass().getSimpleName()
            : failure.getClass().getSimpleName() + ": " + message;
    }

    private final class WorkerLoop implements Runnable {
        @Override
        public void run() {
            try {
                while (accepting.get() || !queue.isEmpty()) {
                    ObservationEvent event = queue.poll(100L, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        process(event);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                recordPipelineFailure(interrupted);
            } catch (RuntimeException failure) {
                recordPipelineFailure(failure);
            } finally {
                accepting.set(false);
                publishHudSnapshot();
                stopped.countDown();
            }
        }
    }
}
