package com.bedwarsbot.observation;

public final class ObservationHudSnapshot {
    private final SparseBlockOverlay.Snapshot overlaySnapshot;
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

    public ObservationHudSnapshot(
        SparseBlockOverlay.Snapshot overlaySnapshot,
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
        if (overlaySnapshot == null) {
            throw new IllegalArgumentException("overlaySnapshot must not be null");
        }
        this.overlaySnapshot = overlaySnapshot;
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

    public SparseBlockOverlay.Snapshot getOverlaySnapshot() {
        return overlaySnapshot;
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
