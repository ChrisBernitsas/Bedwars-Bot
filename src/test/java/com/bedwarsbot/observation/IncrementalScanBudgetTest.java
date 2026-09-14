package com.bedwarsbot.observation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class IncrementalScanBudgetTest {
    @Test
    public void blockBudgetStopsIndividualCopies() {
        IncrementalScanBudget budget = new IncrementalScanBudget(
            2,
            1_500_000L,
            new ConstantClock(100L)
        );

        assertTrue(budget.tryCopyBlock());
        assertTrue(budget.tryCopyBlock());
        assertFalse(budget.tryCopyBlock());
        assertEquals(2, budget.getCopiedBlocks());
        assertEquals(IncrementalScanBudget.StopReason.BLOCK_BUDGET,
            budget.getStopReason());
    }

    @Test
    public void timeBudgetStopsBeforeTheNextCopy() {
        IncrementalScanBudget budget = new IncrementalScanBudget(
            4096,
            10L,
            new ScriptedClock(0L, 1L, 9L, 10L)
        );

        assertTrue(budget.tryCopyBlock());
        assertTrue(budget.tryCopyBlock());
        assertFalse(budget.tryCopyBlock());
        assertEquals(2, budget.getCopiedBlocks());
        assertEquals(10L, budget.getElapsedNanos());
        assertEquals(IncrementalScanBudget.StopReason.TIME_BUDGET,
            budget.getStopReason());
    }

    private static final class ConstantClock implements NanoClock {
        private final long value;

        private ConstantClock(long value) {
            this.value = value;
        }

        @Override
        public long nanoTime() {
            return value;
        }
    }

    private static final class ScriptedClock implements NanoClock {
        private final long[] values;
        private int index;

        private ScriptedClock(long... values) {
            this.values = values;
        }

        @Override
        public long nanoTime() {
            int selected = Math.min(index, values.length - 1);
            index++;
            return values[selected];
        }
    }
}
