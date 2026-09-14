package com.bedwarsbot.observation;

public final class ChunkSectionSnapshot {
    public static final int SECTION_EDGE = 16;
    public static final int BLOCKS_PER_SECTION = 4096;
    public static final int SECTIONS_PER_CHUNK = 16;
    public static final int AIR_STATE_ID = 0;

    private final int sectionIndex;
    private final char uniformStateId;
    private final char[] stateIds;

    private ChunkSectionSnapshot(
        int sectionIndex,
        char uniformStateId,
        char[] stateIds,
        boolean copyDenseData
    ) {
        if (sectionIndex < 0 || sectionIndex >= SECTIONS_PER_CHUNK) {
            throw new IllegalArgumentException("sectionIndex must be between 0 and 15");
        }
        if (stateIds != null && stateIds.length != BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("dense section must contain exactly 4096 states");
        }
        this.sectionIndex = sectionIndex;
        this.uniformStateId = uniformStateId;
        this.stateIds = stateIds == null
            ? null
            : (copyDenseData ? stateIds.clone() : stateIds);
    }

    public static ChunkSectionSnapshot uniform(int sectionIndex, int stateId) {
        return new ChunkSectionSnapshot(sectionIndex, requireStateId(stateId), null, false);
    }

    public static ChunkSectionSnapshot copyDense(int sectionIndex, char[] stateIds) {
        if (stateIds == null) {
            throw new IllegalArgumentException("stateIds must not be null");
        }
        return new ChunkSectionSnapshot(sectionIndex, (char) AIR_STATE_ID, stateIds, true);
    }

    static ChunkSectionSnapshot takeOwnershipDense(int sectionIndex, char[] stateIds) {
        if (stateIds == null) {
            throw new IllegalArgumentException("stateIds must not be null");
        }
        return new ChunkSectionSnapshot(sectionIndex, (char) AIR_STATE_ID, stateIds, false);
    }

    public int getSectionIndex() {
        return sectionIndex;
    }

    public boolean isUniform() {
        return stateIds == null;
    }

    public int getStateId(int localX, int localY, int localZ) {
        if (localX < 0 || localX >= SECTION_EDGE
            || localY < 0 || localY >= SECTION_EDGE
            || localZ < 0 || localZ >= SECTION_EDGE) {
            throw new IllegalArgumentException("local coordinates must be between 0 and 15");
        }
        return getStateId(index(localX, localY, localZ));
    }

    public int getStateId(int index) {
        if (index < 0 || index >= BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("section index must be between 0 and 4095");
        }
        return stateIds == null ? uniformStateId : stateIds[index];
    }

    public char[] copyStateIds() {
        if (stateIds != null) {
            return stateIds.clone();
        }
        char[] copy = new char[BLOCKS_PER_SECTION];
        if (uniformStateId != AIR_STATE_ID) {
            java.util.Arrays.fill(copy, uniformStateId);
        }
        return copy;
    }

    private static int index(int localX, int localY, int localZ) {
        return localY << 8 | localZ << 4 | localX;
    }

    private static char requireStateId(int stateId) {
        if (stateId < 0 || stateId > Character.MAX_VALUE) {
            throw new IllegalArgumentException("stateId must fit in an unsigned 16-bit value");
        }
        return (char) stateId;
    }
}
