package com.bedwarsbot.observation;

public final class ChunkSnapshotKey {
    private final int dimension;
    private final int chunkX;
    private final int chunkZ;

    public ChunkSnapshotKey(int dimension, int chunkX, int chunkZ) {
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public int getDimension() {
        return dimension;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public boolean contains(BlockPosition position) {
        return position != null
            && dimension == position.getDimension()
            && chunkX == position.getChunkX()
            && chunkZ == position.getChunkZ();
    }

    public String toCompactString() {
        return "d=" + dimension + " chunk=" + chunkX + ',' + chunkZ;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChunkSnapshotKey)) {
            return false;
        }
        ChunkSnapshotKey that = (ChunkSnapshotKey) other;
        return dimension == that.dimension && chunkX == that.chunkX && chunkZ == that.chunkZ;
    }

    @Override
    public int hashCode() {
        int result = dimension;
        result = 31 * result + chunkX;
        result = 31 * result + chunkZ;
        return result;
    }
}
