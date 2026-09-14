package com.bedwarsbot.observation;

public final class ChunkSnapshotEvent {
    public static final int SCHEMA_VERSION = 1;

    public enum Type {
        SCHEDULED,
        SECTION_CAPTURED,
        COMPLETED,
        ABORTED,
        UNLOADED,
        DIMENSION_UNLOADED,
        BLOCK_STATE_OBSERVED,
        SCAN_TICK
    }

    private final Type type;
    private final long captureSequence;
    private final long clientTick;
    private final Long worldTick;
    private final long capturedNanos;
    private final ChunkSnapshotKey chunkKey;
    private final long loadGeneration;
    private final ChunkSectionSnapshot sectionSnapshot;
    private final BlockPosition blockPosition;
    private final int blockStateId;
    private final int capturedSections;
    private final String outcome;
    private final String reason;
    private final int blocksCopiedThisTick;
    private final long scanElapsedNanos;
    private final IncrementalScanBudget.StopReason scanStopReason;
    private final long maxObservedScanNanos;
    private final long partialSectionResumeCount;

    private ChunkSnapshotEvent(
        Type type,
        long captureSequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        ChunkSnapshotKey chunkKey,
        long loadGeneration,
        ChunkSectionSnapshot sectionSnapshot,
        BlockPosition blockPosition,
        int blockStateId,
        int capturedSections,
        String outcome,
        String reason,
        int blocksCopiedThisTick,
        long scanElapsedNanos,
        IncrementalScanBudget.StopReason scanStopReason,
        long maxObservedScanNanos,
        long partialSectionResumeCount
    ) {
        if (type == null || chunkKey == null) {
            throw new IllegalArgumentException("snapshot event type and chunk key must not be null");
        }
        if (captureSequence < 0L
            || (type != Type.DIMENSION_UNLOADED && loadGeneration <= 0L)
            || loadGeneration < 0L
            || capturedSections < 0
            || capturedSections > ChunkSectionSnapshot.SECTIONS_PER_CHUNK) {
            throw new IllegalArgumentException("snapshot event counters are invalid");
        }
        if (type == Type.SECTION_CAPTURED && sectionSnapshot == null) {
            throw new IllegalArgumentException("section event requires immutable section data");
        }
        if (type != Type.SECTION_CAPTURED && sectionSnapshot != null) {
            throw new IllegalArgumentException("only section events may contain section data");
        }
        if (type == Type.BLOCK_STATE_OBSERVED
            && (blockPosition == null || !chunkKey.contains(blockPosition))) {
            throw new IllegalArgumentException("block event requires a position in its chunk");
        }
        if (blockStateId < 0 || blockStateId > Character.MAX_VALUE) {
            throw new IllegalArgumentException("blockStateId must fit in an unsigned 16-bit value");
        }
        if (blocksCopiedThisTick < 0
            || scanElapsedNanos < 0L
            || maxObservedScanNanos < 0L
            || partialSectionResumeCount < 0L
            || scanStopReason == null) {
            throw new IllegalArgumentException("snapshot scan metrics are invalid");
        }
        this.type = type;
        this.captureSequence = captureSequence;
        this.clientTick = clientTick;
        this.worldTick = worldTick;
        this.capturedNanos = capturedNanos;
        this.chunkKey = chunkKey;
        this.loadGeneration = loadGeneration;
        this.sectionSnapshot = sectionSnapshot;
        this.blockPosition = blockPosition;
        this.blockStateId = blockStateId;
        this.capturedSections = capturedSections;
        this.outcome = outcome == null ? "" : outcome;
        this.reason = reason == null ? "" : reason;
        this.blocksCopiedThisTick = blocksCopiedThisTick;
        this.scanElapsedNanos = scanElapsedNanos;
        this.scanStopReason = scanStopReason;
        this.maxObservedScanNanos = maxObservedScanNanos;
        this.partialSectionResumeCount = partialSectionResumeCount;
    }

    public static ChunkSnapshotEvent scheduled(
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        ChunkSnapshotKey key,
        long generation,
        String outcome
    ) {
        return create(Type.SCHEDULED, sequence, clientTick, worldTick, capturedNanos,
            key, generation, null, null, 0, 0, outcome, "");
    }

    public static ChunkSnapshotEvent sectionCaptured(
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        ChunkSnapshotKey key,
        long generation,
        ChunkSectionSnapshot section,
        int capturedSections
    ) {
        return create(Type.SECTION_CAPTURED, sequence, clientTick, worldTick, capturedNanos,
            key, generation, section, null, 0, capturedSections, "CAPTURED", "");
    }

    public static ChunkSnapshotEvent completed(
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        ChunkSnapshotKey key,
        long generation
    ) {
        return create(Type.COMPLETED, sequence, clientTick, worldTick, capturedNanos,
            key, generation, null, null, 0, ChunkSectionSnapshot.SECTIONS_PER_CHUNK,
            "COMPLETE", "");
    }

    public static ChunkSnapshotEvent aborted(
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        ChunkSnapshotKey key,
        long generation,
        int capturedSections,
        String reason
    ) {
        return create(Type.ABORTED, sequence, clientTick, worldTick, capturedNanos,
            key, generation, null, null, 0, capturedSections, "ABORTED", reason);
    }

    public static ChunkSnapshotEvent unloaded(
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        ChunkSnapshotKey key,
        long generation,
        int capturedSections
    ) {
        return create(Type.UNLOADED, sequence, clientTick, worldTick, capturedNanos,
            key, generation, null, null, 0, capturedSections, "STALE", "chunk_unloaded");
    }

    public static ChunkSnapshotEvent dimensionUnloaded(
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        int dimension,
        long generation
    ) {
        return create(Type.DIMENSION_UNLOADED, sequence, clientTick, worldTick, capturedNanos,
            new ChunkSnapshotKey(dimension, 0, 0), generation, null, null, 0, 0,
            "STALE", "dimension_unloaded");
    }

    public static ChunkSnapshotEvent blockStateObserved(
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        ChunkSnapshotKey key,
        long generation,
        BlockPosition position,
        int stateId
    ) {
        return create(Type.BLOCK_STATE_OBSERVED, sequence, clientTick, worldTick,
            capturedNanos, key, generation, null, position, stateId, 0, "RECONCILED", "");
    }

    public static ChunkSnapshotEvent scanTick(
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        ChunkSnapshotKey key,
        long generation,
        int blocksCopiedThisTick,
        long scanElapsedNanos,
        IncrementalScanBudget.StopReason stopReason,
        long maxObservedScanNanos,
        long partialSectionResumeCount
    ) {
        return new ChunkSnapshotEvent(
            Type.SCAN_TICK,
            sequence,
            clientTick,
            worldTick,
            capturedNanos,
            key,
            generation,
            null,
            null,
            0,
            0,
            "METRICS",
            "",
            blocksCopiedThisTick,
            scanElapsedNanos,
            stopReason,
            maxObservedScanNanos,
            partialSectionResumeCount
        );
    }

    private static ChunkSnapshotEvent create(
        Type type,
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        ChunkSnapshotKey key,
        long generation,
        ChunkSectionSnapshot section,
        BlockPosition position,
        int stateId,
        int capturedSections,
        String outcome,
        String reason
    ) {
        return new ChunkSnapshotEvent(type, sequence, clientTick, worldTick, capturedNanos,
            key, generation, section, position, stateId, capturedSections, outcome, reason,
            0, 0L, IncrementalScanBudget.StopReason.NONE, 0L, 0L);
    }

    public Type getType() {
        return type;
    }

    public long getCaptureSequence() {
        return captureSequence;
    }

    public long getClientTick() {
        return clientTick;
    }

    public Long getWorldTick() {
        return worldTick;
    }

    public long getCapturedNanos() {
        return capturedNanos;
    }

    public ChunkSnapshotKey getChunkKey() {
        return chunkKey;
    }

    public long getLoadGeneration() {
        return loadGeneration;
    }

    public ChunkSectionSnapshot getSectionSnapshot() {
        return sectionSnapshot;
    }

    public BlockPosition getBlockPosition() {
        return blockPosition;
    }

    public int getBlockStateId() {
        return blockStateId;
    }

    public int getCapturedSections() {
        return capturedSections;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getReason() {
        return reason;
    }

    public int getBlocksCopiedThisTick() {
        return blocksCopiedThisTick;
    }

    public long getScanElapsedNanos() {
        return scanElapsedNanos;
    }

    public IncrementalScanBudget.StopReason getScanStopReason() {
        return scanStopReason;
    }

    public long getMaxObservedScanNanos() {
        return maxObservedScanNanos;
    }

    public long getPartialSectionResumeCount() {
        return partialSectionResumeCount;
    }

    public boolean shouldLog() {
        return type != Type.BLOCK_STATE_OBSERVED;
    }
}
