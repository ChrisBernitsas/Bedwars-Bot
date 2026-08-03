package com.bedwarsbot.observation;

public final class BlockPosition {
    private final int dimension;
    private final int x;
    private final int y;
    private final int z;

    public BlockPosition(int dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int getDimension() {
        return dimension;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public int getChunkX() {
        return x >> 4;
    }

    public int getChunkZ() {
        return z >> 4;
    }

    public String toCompactString() {
        return "d=" + dimension + " " + x + ',' + y + ',' + z;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BlockPosition)) {
            return false;
        }
        BlockPosition that = (BlockPosition) other;
        return dimension == that.dimension && x == that.x && y == that.y && z == that.z;
    }

    @Override
    public int hashCode() {
        int result = dimension;
        result = 31 * result + x;
        result = 31 * result + y;
        result = 31 * result + z;
        return result;
    }

    @Override
    public String toString() {
        return toCompactString();
    }
}
