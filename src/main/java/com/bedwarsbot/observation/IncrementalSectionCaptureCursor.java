package com.bedwarsbot.observation;

public final class IncrementalSectionCaptureCursor {
    private final ChunkSnapshotKey chunkKey;
    private final long loadGeneration;
    private final int sectionIndex;
    private char[] copiedStateIds = new char[ChunkSectionSnapshot.BLOCKS_PER_SECTION];
    private int positionWithinSection;

    public IncrementalSectionCaptureCursor(
        ChunkSnapshotKey chunkKey,
        long loadGeneration,
        int sectionIndex
    ) {
        if (chunkKey == null) {
            throw new IllegalArgumentException("chunkKey must not be null");
        }
        if (loadGeneration <= 0L) {
            throw new IllegalArgumentException("loadGeneration must be positive");
        }
        if (sectionIndex < 0 || sectionIndex >= ChunkSectionSnapshot.SECTIONS_PER_CHUNK) {
            throw new IllegalArgumentException("sectionIndex must be between 0 and 15");
        }
        this.chunkKey = chunkKey;
        this.loadGeneration = loadGeneration;
        this.sectionIndex = sectionIndex;
    }

    public int copyFrom(char[] liveStateIds, IncrementalScanBudget budget) {
        if (budget == null) {
            throw new IllegalArgumentException("budget must not be null");
        }
        if (copiedStateIds == null) {
            throw new IllegalStateException("completed cursor cannot be reused");
        }
        if (liveStateIds != null
            && liveStateIds.length != ChunkSectionSnapshot.BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("live section must contain exactly 4096 states");
        }

        int copiedBefore = positionWithinSection;
        while (positionWithinSection < ChunkSectionSnapshot.BLOCKS_PER_SECTION
            && budget.tryCopyBlock()) {
            copiedStateIds[positionWithinSection] = liveStateIds == null
                ? (char) ChunkSectionSnapshot.AIR_STATE_ID
                : liveStateIds[positionWithinSection];
            positionWithinSection++;
        }
        return positionWithinSection - copiedBefore;
    }

    public boolean patchIfCopied(int localIndex, int stateId) {
        if (localIndex < 0 || localIndex >= ChunkSectionSnapshot.BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("localIndex must be between 0 and 4095");
        }
        if (stateId < 0 || stateId > Character.MAX_VALUE) {
            throw new IllegalArgumentException("stateId must fit in an unsigned 16-bit value");
        }
        if (copiedStateIds == null || localIndex >= positionWithinSection) {
            return false;
        }
        copiedStateIds[localIndex] = (char) stateId;
        return true;
    }

    public ChunkSectionSnapshot completeSnapshot() {
        if (!isComplete()) {
            throw new IllegalStateException("section capture is not complete");
        }
        if (copiedStateIds == null) {
            throw new IllegalStateException("completed cursor cannot be reused");
        }
        char[] completed = copiedStateIds;
        copiedStateIds = null;
        return ChunkSectionSnapshot.takeOwnershipDense(sectionIndex, completed);
    }

    public boolean matches(ChunkSnapshotKey key, long generation, int candidateSection) {
        return chunkKey.equals(key)
            && loadGeneration == generation
            && sectionIndex == candidateSection;
    }

    public ChunkSnapshotKey getChunkKey() {
        return chunkKey;
    }

    public long getLoadGeneration() {
        return loadGeneration;
    }

    public int getSectionIndex() {
        return sectionIndex;
    }

    public int getPositionWithinSection() {
        return positionWithinSection;
    }

    public boolean isComplete() {
        return positionWithinSection == ChunkSectionSnapshot.BLOCKS_PER_SECTION;
    }
}
