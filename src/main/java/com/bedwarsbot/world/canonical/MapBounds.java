package com.bedwarsbot.world.canonical;

public final class MapBounds {
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public MapBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }

    public boolean isOrdered() {
        return minX <= maxX && minY <= maxY && minZ <= maxZ;
    }

    public boolean contains(int x, int y, int z) {
        return isOrdered()
            && x >= minX && x <= maxX
            && y >= minY && y <= maxY
            && z >= minZ && z <= maxZ;
    }

    public boolean intersectsChunk(int chunkX, int chunkZ) {
        long chunkMinX = (long) chunkX * 16L;
        long chunkMaxX = chunkMinX + 15L;
        long chunkMinZ = (long) chunkZ * 16L;
        long chunkMaxZ = chunkMinZ + 15L;
        return isOrdered()
            && chunkMaxX >= minX && chunkMinX <= maxX
            && chunkMaxZ >= minZ && chunkMinZ <= maxZ;
    }

    public boolean intersectsSection(int sectionIndex) {
        long sectionMinY = (long) sectionIndex * 16L;
        long sectionMaxY = sectionMinY + 15L;
        return isOrdered() && sectionMaxY >= minY && sectionMinY <= maxY;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MapBounds)) return false;
        MapBounds that = (MapBounds) other;
        return minX == that.minX && minY == that.minY && minZ == that.minZ
            && maxX == that.maxX && maxY == that.maxY && maxZ == that.maxZ;
    }

    @Override
    public int hashCode() {
        int result = minX;
        result = 31 * result + minY;
        result = 31 * result + minZ;
        result = 31 * result + maxX;
        result = 31 * result + maxY;
        result = 31 * result + maxZ;
        return result;
    }

    @Override
    public String toString() {
        return "[" + minX + ',' + minY + ',' + minZ + "]..["
            + maxX + ',' + maxY + ',' + maxZ + ']';
    }
}
