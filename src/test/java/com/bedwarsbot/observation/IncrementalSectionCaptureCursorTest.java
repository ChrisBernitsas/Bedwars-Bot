package com.bedwarsbot.observation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class IncrementalSectionCaptureCursorTest {
    private static final ChunkSnapshotKey KEY = new ChunkSnapshotKey(0, 4, -2);

    @Test
    public void pausesAndResumesInsideASectionWithoutPublishingPartialData() {
        char[] live = new char[ChunkSectionSnapshot.BLOCKS_PER_SECTION];
        for (int index = 0; index < live.length; index++) {
            live[index] = (char) (index & 255);
        }
        IncrementalSectionCaptureCursor cursor =
            new IncrementalSectionCaptureCursor(KEY, 7L, 3);

        IncrementalScanBudget firstTick = new IncrementalScanBudget(
            1000, 1_500_000L, new ConstantClock(0L)
        );
        assertEquals(1000, cursor.copyFrom(live, firstTick));
        assertEquals(1000, cursor.getPositionWithinSection());
        assertFalse(cursor.isComplete());

        IncrementalScanBudget secondTick = new IncrementalScanBudget(
            4096, 1_500_000L, new ConstantClock(0L)
        );
        assertEquals(3096, cursor.copyFrom(live, secondTick));
        assertTrue(cursor.isComplete());

        ChunkSectionSnapshot completed = cursor.completeSnapshot();
        assertEquals(3, completed.getSectionIndex());
        assertEquals(live[0], completed.getStateId(0));
        assertEquals(live[999], completed.getStateId(999));
        assertEquals(live[4095], completed.getStateId(4095));
    }

    @Test(expected = IllegalStateException.class)
    public void partialSectionCannotBePublished() {
        IncrementalSectionCaptureCursor cursor =
            new IncrementalSectionCaptureCursor(KEY, 1L, 0);
        cursor.copyFrom(
            new char[ChunkSectionSnapshot.BLOCKS_PER_SECTION],
            new IncrementalScanBudget(1, 1_500_000L, new ConstantClock(0L))
        );
        cursor.completeSnapshot();
    }

    @Test
    public void newerObservedStatePatchesAnAlreadyCopiedPosition() {
        IncrementalSectionCaptureCursor cursor =
            new IncrementalSectionCaptureCursor(KEY, 1L, 0);
        cursor.copyFrom(
            new char[ChunkSectionSnapshot.BLOCKS_PER_SECTION],
            new IncrementalScanBudget(2, 1_500_000L, new ConstantClock(0L))
        );

        assertTrue(cursor.patchIfCopied(1, 35));
        assertFalse(cursor.patchIfCopied(2, 35));
    }

    @Test
    public void timeBudgetPausesInsideASection() {
        IncrementalSectionCaptureCursor cursor =
            new IncrementalSectionCaptureCursor(KEY, 2L, 4);
        IncrementalScanBudget budget = new IncrementalScanBudget(
            4096,
            10L,
            new ScriptedClock(0L, 1L, 9L, 10L)
        );

        assertEquals(2, cursor.copyFrom(
            new char[ChunkSectionSnapshot.BLOCKS_PER_SECTION],
            budget
        ));
        assertEquals(2, cursor.getPositionWithinSection());
        assertFalse(cursor.isComplete());
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
