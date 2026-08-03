package com.bedwarsbot.observation;

public final class BlockStateSnapshot {
    private final int blockId;
    private final String registryName;
    private final int metadata;

    public BlockStateSnapshot(int blockId, String registryName, int metadata) {
        if (blockId < 0) {
            throw new IllegalArgumentException("blockId must not be negative");
        }
        if (registryName == null || registryName.isEmpty()) {
            throw new IllegalArgumentException("registryName must not be empty");
        }
        if (metadata < 0 || metadata > 15) {
            throw new IllegalArgumentException("metadata must be between 0 and 15");
        }
        this.blockId = blockId;
        this.registryName = registryName;
        this.metadata = metadata;
    }

    public int getBlockId() {
        return blockId;
    }

    public String getRegistryName() {
        return registryName;
    }

    public int getMetadata() {
        return metadata;
    }

    public String toCompactString() {
        return registryName + '#' + metadata;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BlockStateSnapshot)) {
            return false;
        }
        BlockStateSnapshot that = (BlockStateSnapshot) other;
        return blockId == that.blockId
            && metadata == that.metadata
            && registryName.equals(that.registryName);
    }

    @Override
    public int hashCode() {
        int result = blockId;
        result = 31 * result + registryName.hashCode();
        result = 31 * result + metadata;
        return result;
    }

    @Override
    public String toString() {
        return toCompactString();
    }
}
