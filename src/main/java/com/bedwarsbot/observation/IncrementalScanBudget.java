package com.bedwarsbot.observation;

public final class IncrementalScanBudget {
    public enum StopReason {
        NONE,
        BLOCK_BUDGET,
        TIME_BUDGET
    }

    private final int maxBlocks;
    private final long maxNanos;
    private final NanoClock clock;
    private final long startedNanos;

    private int copiedBlocks;
    private long lastObservedNanos;
    private StopReason stopReason = StopReason.NONE;

    public IncrementalScanBudget(
        int maxBlocks,
        long maxNanos,
        NanoClock clock
    ) {
        if (maxBlocks <= 0 || maxNanos <= 0L) {
            throw new IllegalArgumentException("scan budgets must be positive");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.maxBlocks = maxBlocks;
        this.maxNanos = maxNanos;
        this.clock = clock;
        this.startedNanos = clock.nanoTime();
        this.lastObservedNanos = startedNanos;
    }

    public boolean tryCopyBlock() {
        if (!hasBudgetRemaining()) {
            return false;
        }
        copiedBlocks++;
        return true;
    }

    public boolean hasBudgetRemaining() {
        if (copiedBlocks >= maxBlocks) {
            stopReason = StopReason.BLOCK_BUDGET;
            return false;
        }
        lastObservedNanos = clock.nanoTime();
        if (elapsedSinceStart(lastObservedNanos) >= maxNanos) {
            stopReason = StopReason.TIME_BUDGET;
            return false;
        }
        return true;
    }

    public void observeEnd() {
        lastObservedNanos = clock.nanoTime();
    }

    public int getCopiedBlocks() {
        return copiedBlocks;
    }

    public long getElapsedNanos() {
        return elapsedSinceStart(lastObservedNanos);
    }

    public StopReason getStopReason() {
        return stopReason;
    }

    private long elapsedSinceStart(long nowNanos) {
        return Math.max(0L, nowNanos - startedNanos);
    }
}
