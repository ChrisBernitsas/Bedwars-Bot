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

public final class ChunkSnapshotPipeline implements AutoCloseable {
    public static final int DEFAULT_QUEUE_CAPACITY = 512;

    private static final long CLOSE_TIMEOUT_SECONDS = 5L;

    private final AsyncSessionLogger sessionLogger;
    private final BoundedChunkSnapshotQueue queue;
    private final ClientObservedChunkStore store = new ClientObservedChunkStore();
    private final ChunkSnapshotEventSerializer serializer = new ChunkSnapshotEventSerializer();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicLong processedEvents = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong lastProcessingNanos = new AtomicLong();
    private final AtomicLong totalProcessingNanos = new AtomicLong();
    private final AtomicLong maxProcessingNanos = new AtomicLong();
    private final AtomicReference<String> failureMessage = new AtomicReference<String>();
    private final AtomicReference<PipelineSnapshot> pipelineSnapshot =
        new AtomicReference<PipelineSnapshot>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Object captureLock = new Object();
    private final Thread workerThread;

    public ChunkSnapshotPipeline(AsyncSessionLogger sessionLogger) {
        this(sessionLogger, DEFAULT_QUEUE_CAPACITY);
    }

    public ChunkSnapshotPipeline(AsyncSessionLogger sessionLogger, int queueCapacity) {
        if (sessionLogger == null) {
            throw new IllegalArgumentException("sessionLogger must not be null");
        }
        this.sessionLogger = sessionLogger;
        this.queue = new BoundedChunkSnapshotQueue(queueCapacity);
        publishSnapshot();
        workerThread = new Thread(new WorkerLoop(), "bedwarsbot-chunk-snapshot-worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public boolean tryCapture(ChunkSnapshotEvent event) {
        boolean accepted;
        synchronized (captureLock) {
            if (!accepting.get()) {
                return false;
            }
            accepted = queue.offer(event);
        }
        publishSnapshot();
        return accepted;
    }

    public void recordCaptureFailure(RuntimeException failure) {
        recordFailure(failure);
    }

    public AtomicReference<PipelineSnapshot> getPipelineSnapshotReference() {
        return pipelineSnapshot;
    }

    public ClientObservedChunkStore.ObservedBlockValue getObservedBlock(BlockPosition position) {
        return store.lookup(position);
    }

    public void logSummary(ChunkSnapshotHudSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        PipelineSnapshot pipeline = snapshot.getPipelineSnapshot();
        ClientObservedChunkStore.StoreSnapshot chunks = pipeline.getStoreSnapshot();
        Map<String, String> details = new LinkedHashMap<String, String>();
        details.put("snapshot_schema_version", Integer.toString(ChunkSnapshotEvent.SCHEMA_VERSION));
        details.put("pending_chunks", Integer.toString(snapshot.getPendingChunks()));
        details.put("pending_sections", Integer.toString(snapshot.getPendingSections()));
        details.put("scheduled_loads", Long.toString(snapshot.getScheduledLoads()));
        details.put("duplicate_loads", Long.toString(snapshot.getDuplicateLoads()));
        details.put("dropped_schedules", Long.toString(snapshot.getDroppedSchedules()));
        details.put("scanned_blocks", Long.toString(snapshot.getScannedBlocks()));
        details.put("scanned_sections", Long.toString(snapshot.getScannedSections()));
        details.put("blocks_copied_this_tick", Integer.toString(
            snapshot.getLastBlocksCopiedThisTick()
        ));
        details.put("last_scan_nanos", Long.toString(snapshot.getLastScanNanos()));
        details.put("average_scan_nanos", Long.toString(snapshot.getAverageScanNanos()));
        details.put("max_scan_nanos", Long.toString(snapshot.getMaxScanNanos()));
        details.put("scan_stop_reason", snapshot.getLastScanStopReason().name());
        details.put("block_budget_stop_count", Long.toString(
            snapshot.getBlockBudgetStopCount()
        ));
        details.put("time_budget_stop_count", Long.toString(
            snapshot.getTimeBudgetStopCount()
        ));
        details.put("partial_section_resume_count", Long.toString(
            snapshot.getPartialSectionResumeCount()
        ));
        details.put("completed_snapshots", Long.toString(chunks.getCompletedSnapshots()));
        details.put("aborted_snapshots", Long.toString(chunks.getAbortedSnapshots()));
        details.put("partial_snapshots", Long.toString(chunks.getPartialSnapshots()));
        details.put("complete_chunks", Integer.toString(chunks.getCompleteChunks()));
        details.put("partial_chunks", Integer.toString(chunks.getPartialChunks()));
        details.put("stale_chunks", Integer.toString(chunks.getStaleChunks()));
        details.put("covered_sections", Integer.toString(chunks.getCoveredSections()));
        details.put("rejected_old_generations", Long.toString(
            chunks.getRejectedOldGenerations()
        ));
        details.put("rejected_out_of_order", Long.toString(chunks.getRejectedOutOfOrder()));
        details.put("accepted_events", Long.toString(pipeline.getAcceptedEvents()));
        details.put("processed_events", Long.toString(pipeline.getProcessedEvents()));
        details.put("dropped_events", Long.toString(pipeline.getDroppedEvents()));
        details.put("failure_count", Long.toString(pipeline.getFailureCount()));
        details.put("failure_message", pipeline.getFailureMessage() == null
            ? ""
            : pipeline.getFailureMessage());
        details.put("queue_capacity", Integer.toString(pipeline.getQueueCapacity()));
        details.put("queue_depth", Integer.toString(pipeline.getQueueDepth()));
        details.put("last_processing_nanos", Long.toString(pipeline.getLastProcessingNanos()));
        details.put("average_processing_nanos", Long.toString(
            pipeline.getAverageProcessingNanos()
        ));
        details.put("max_processing_nanos", Long.toString(pipeline.getMaxProcessingNanos()));
        sessionLogger.tryLog(
            "chunk_snapshot",
            "chunk_snapshot_pipeline_summary",
            -1L,
            null,
            details
        );
    }

    @Override
    public void close() {
        synchronized (captureLock) {
            accepting.set(false);
        }
        try {
            if (!stopped.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                recordFailure(new IOException("chunk snapshot worker did not stop within timeout"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            recordFailure(interrupted);
        }
    }

    private void process(ChunkSnapshotEvent event) {
        long startedNanos = System.nanoTime();
        try {
            ClientObservedChunkStore.ApplyResult result = store.apply(event);
            ClientObservedChunkStore.StoreSnapshot storeSnapshot = store.snapshot();
            if (event.shouldLog()) {
                sessionLogger.tryLog(
                    "chunk_snapshot",
                    serializer.eventType(event),
                    event.getClientTick(),
                    event.getWorldTick(),
                    serializer.details(event, result, storeSnapshot)
                );
            }
        } catch (RuntimeException failure) {
            recordFailure(failure);
            Map<String, String> details = new LinkedHashMap<String, String>();
            details.put("exception", failure.getClass().getName());
            details.put("message", failure.getMessage() == null ? "" : failure.getMessage());
            details.put("snapshot_capture_sequence", Long.toString(event.getCaptureSequence()));
            details.put("snapshot_schema_version", Integer.toString(ChunkSnapshotEvent.SCHEMA_VERSION));
            sessionLogger.tryLog(
                "chunk_snapshot",
                "chunk_snapshot_processing_failure",
                event.getClientTick(),
                event.getWorldTick(),
                details
            );
        } finally {
            long duration = System.nanoTime() - startedNanos;
            lastProcessingNanos.set(duration);
            totalProcessingNanos.addAndGet(duration);
            processedEvents.incrementAndGet();
            updateMaximum(duration);
            publishSnapshot();
        }
    }

    private void recordFailure(Throwable failure) {
        failureCount.incrementAndGet();
        failureMessage.set(describeFailure(failure));
        publishSnapshot();
    }

    private void updateMaximum(long candidate) {
        long current = maxProcessingNanos.get();
        while (candidate > current && !maxProcessingNanos.compareAndSet(current, candidate)) {
            current = maxProcessingNanos.get();
        }
    }

    private void publishSnapshot() {
        long processed = processedEvents.get();
        long average = processed == 0L ? 0L : totalProcessingNanos.get() / processed;
        pipelineSnapshot.set(new PipelineSnapshot(
            store.snapshot(),
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
            return "unknown chunk snapshot failure";
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
                    ChunkSnapshotEvent event = queue.poll(100L, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        process(event);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                recordFailure(interrupted);
            } catch (RuntimeException failure) {
                recordFailure(failure);
            } finally {
                accepting.set(false);
                publishSnapshot();
                stopped.countDown();
            }
        }
    }

    public static final class PipelineSnapshot {
        private final ClientObservedChunkStore.StoreSnapshot storeSnapshot;
        private final int queueDepth;
        private final int queueCapacity;
        private final long acceptedEvents;
        private final long droppedEvents;
        private final long processedEvents;
        private final long failureCount;
        private final long lastProcessingNanos;
        private final long averageProcessingNanos;
        private final long maxProcessingNanos;
        private final String failureMessage;

        private PipelineSnapshot(
            ClientObservedChunkStore.StoreSnapshot storeSnapshot,
            int queueDepth,
            int queueCapacity,
            long acceptedEvents,
            long droppedEvents,
            long processedEvents,
            long failureCount,
            long lastProcessingNanos,
            long averageProcessingNanos,
            long maxProcessingNanos,
            String failureMessage
        ) {
            this.storeSnapshot = storeSnapshot;
            this.queueDepth = queueDepth;
            this.queueCapacity = queueCapacity;
            this.acceptedEvents = acceptedEvents;
            this.droppedEvents = droppedEvents;
            this.processedEvents = processedEvents;
            this.failureCount = failureCount;
            this.lastProcessingNanos = lastProcessingNanos;
            this.averageProcessingNanos = averageProcessingNanos;
            this.maxProcessingNanos = maxProcessingNanos;
            this.failureMessage = failureMessage;
        }

        public ClientObservedChunkStore.StoreSnapshot getStoreSnapshot() {
            return storeSnapshot;
        }

        public int getQueueDepth() {
            return queueDepth;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public long getAcceptedEvents() {
            return acceptedEvents;
        }

        public long getDroppedEvents() {
            return droppedEvents;
        }

        public long getProcessedEvents() {
            return processedEvents;
        }

        public long getFailureCount() {
            return failureCount;
        }

        public long getLastProcessingNanos() {
            return lastProcessingNanos;
        }

        public long getAverageProcessingNanos() {
            return averageProcessingNanos;
        }

        public long getMaxProcessingNanos() {
            return maxProcessingNanos;
        }

        public String getFailureMessage() {
            return failureMessage;
        }
    }
}
