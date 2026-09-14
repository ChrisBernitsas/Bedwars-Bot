package com.bedwarsbot.observation;

public final class ChunkSnapshotHudSnapshot {
    private final int pendingChunks;
    private final int pendingSections;
    private final int pendingCapacity;
    private final long scheduledLoads;
    private final long duplicateLoads;
    private final long droppedSchedules;
    private final long scannedSections;
    private final long scannedBlocks;
    private final int lastBlocksCopiedThisTick;
    private final long lastScanNanos;
    private final long averageScanNanos;
    private final long maxScanNanos;
    private final IncrementalScanBudget.StopReason lastScanStopReason;
    private final long blockBudgetStopCount;
    private final long timeBudgetStopCount;
    private final long partialSectionResumeCount;
    private final ChunkSnapshotPipeline.PipelineSnapshot pipelineSnapshot;

    public ChunkSnapshotHudSnapshot(
        int pendingChunks,
        int pendingSections,
        int pendingCapacity,
        long scheduledLoads,
        long duplicateLoads,
        long droppedSchedules,
        long scannedSections,
        long scannedBlocks,
        int lastBlocksCopiedThisTick,
        long lastScanNanos,
        long averageScanNanos,
        long maxScanNanos,
        IncrementalScanBudget.StopReason lastScanStopReason,
        long blockBudgetStopCount,
        long timeBudgetStopCount,
        long partialSectionResumeCount,
        ChunkSnapshotPipeline.PipelineSnapshot pipelineSnapshot
    ) {
        if (pipelineSnapshot == null || lastScanStopReason == null) {
            throw new IllegalArgumentException("snapshot dependencies must not be null");
        }
        this.pendingChunks = pendingChunks;
        this.pendingSections = pendingSections;
        this.pendingCapacity = pendingCapacity;
        this.scheduledLoads = scheduledLoads;
        this.duplicateLoads = duplicateLoads;
        this.droppedSchedules = droppedSchedules;
        this.scannedSections = scannedSections;
        this.scannedBlocks = scannedBlocks;
        this.lastBlocksCopiedThisTick = lastBlocksCopiedThisTick;
        this.lastScanNanos = lastScanNanos;
        this.averageScanNanos = averageScanNanos;
        this.maxScanNanos = maxScanNanos;
        this.lastScanStopReason = lastScanStopReason;
        this.blockBudgetStopCount = blockBudgetStopCount;
        this.timeBudgetStopCount = timeBudgetStopCount;
        this.partialSectionResumeCount = partialSectionResumeCount;
        this.pipelineSnapshot = pipelineSnapshot;
    }

    public int getPendingChunks() {
        return pendingChunks;
    }

    public int getPendingSections() {
        return pendingSections;
    }

    public int getPendingCapacity() {
        return pendingCapacity;
    }

    public long getScheduledLoads() {
        return scheduledLoads;
    }

    public long getDuplicateLoads() {
        return duplicateLoads;
    }

    public long getDroppedSchedules() {
        return droppedSchedules;
    }

    public long getScannedSections() {
        return scannedSections;
    }

    public long getScannedBlocks() {
        return scannedBlocks;
    }

    public int getLastBlocksCopiedThisTick() {
        return lastBlocksCopiedThisTick;
    }

    public long getLastScanNanos() {
        return lastScanNanos;
    }

    public long getAverageScanNanos() {
        return averageScanNanos;
    }

    public long getMaxScanNanos() {
        return maxScanNanos;
    }

    public IncrementalScanBudget.StopReason getLastScanStopReason() {
        return lastScanStopReason;
    }

    public long getBlockBudgetStopCount() {
        return blockBudgetStopCount;
    }

    public long getTimeBudgetStopCount() {
        return timeBudgetStopCount;
    }

    public long getPartialSectionResumeCount() {
        return partialSectionResumeCount;
    }

    public ChunkSnapshotPipeline.PipelineSnapshot getPipelineSnapshot() {
        return pipelineSnapshot;
    }

    public ClientObservedChunkStore.StoreSnapshot getStoreSnapshot() {
        return pipelineSnapshot.getStoreSnapshot();
    }
}
