package com.bedwarsbot.world.canonical;

public final class CanonicalPosition {
    private final int x;
    private final int y;
    private final int z;

    public CanonicalPosition(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CanonicalPosition)) return false;
        CanonicalPosition that = (CanonicalPosition) other;
        return x == that.x && y == that.y && z == that.z;
    }

    @Override
    public int hashCode() {
        int result = x;
        result = 31 * result + y;
        result = 31 * result + z;
        return result;
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}
