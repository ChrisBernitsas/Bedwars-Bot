package com.bedwarsbot.world.canonical;

public final class CanonicalBlockState implements Comparable<CanonicalBlockState> {
    public static final CanonicalBlockState AIR = new CanonicalBlockState(0, 0);

    private final int blockId;
    private final int metadata;

    public CanonicalBlockState(int blockId, int metadata) {
        this.blockId = blockId;
        this.metadata = metadata;
    }

    public int getBlockId() {
        return blockId;
    }

    public int getMetadata() {
        return metadata;
    }

    public boolean isAir() {
        return blockId == 0 && metadata == 0;
    }

    @Override
    public int compareTo(CanonicalBlockState other) {
        int blockComparison = Integer.compare(blockId, other.blockId);
        return blockComparison != 0
            ? blockComparison
            : Integer.compare(metadata, other.metadata);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CanonicalBlockState)) {
            return false;
        }
        CanonicalBlockState that = (CanonicalBlockState) other;
        return blockId == that.blockId && metadata == that.metadata;
    }

    @Override
    public int hashCode() {
        return 31 * blockId + metadata;
    }

    @Override
    public String toString() {
        return blockId + ":" + metadata;
    }
}
