package com.bedwarsbot.observation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class IncrementalChunkSnapshotSchedulerTest {
    @Test
    public void duplicateLoadCallbackKeepsOneGenerationAndOnePendingCapture() {
        IncrementalChunkSnapshotScheduler scheduler = new IncrementalChunkSnapshotScheduler(4);
        ChunkSnapshotKey key = new ChunkSnapshotKey(0, 2, 3);

        IncrementalChunkSnapshotScheduler.ScheduleResult first = scheduler.schedule(key);
        IncrementalChunkSnapshotScheduler.ScheduleResult duplicate = scheduler.schedule(key);

        assertEquals(IncrementalChunkSnapshotScheduler.ScheduleOutcome.SCHEDULED,
            first.getOutcome());
        assertEquals(IncrementalChunkSnapshotScheduler.ScheduleOutcome.DUPLICATE,
            duplicate.getOutcome());
        assertEquals(first.getGeneration(), duplicate.getGeneration());
        assertEquals(1, scheduler.getPendingChunkCount());
        assertEquals(16, scheduler.getPendingSectionCount());
    }

    @Test
    public void unloadDuringCaptureAbortsProgressAndReloadUsesNewGeneration() {
        IncrementalChunkSnapshotScheduler scheduler = new IncrementalChunkSnapshotScheduler(4);
        ChunkSnapshotKey key = new ChunkSnapshotKey(0, -1, 7);
        IncrementalChunkSnapshotScheduler.ScheduleResult first = scheduler.schedule(key);
        IncrementalChunkSnapshotScheduler.CaptureStep step = scheduler.peekNextSection();

        assertTrue(scheduler.sectionCaptured(step).isAccepted());
        IncrementalChunkSnapshotScheduler.UnloadResult unloaded = scheduler.unload(key);
        assertTrue(unloaded.wasCapturing());
        assertEquals(1, unloaded.getCapturedSections());
        assertEquals(0, scheduler.getPendingChunkCount());

        IncrementalChunkSnapshotScheduler.ScheduleResult reloaded = scheduler.schedule(key);
        assertTrue(reloaded.getGeneration() > first.getGeneration());
        assertFalse(scheduler.abort(key, first.getGeneration()).isCurrent());
        assertEquals(Long.valueOf(reloaded.getGeneration()), scheduler.currentGeneration(key));
    }

    @Test
    public void completesExactlySixteenSectionsInOrder() {
        IncrementalChunkSnapshotScheduler scheduler = new IncrementalChunkSnapshotScheduler(2);
        ChunkSnapshotKey key = new ChunkSnapshotKey(0, 0, 0);
        scheduler.schedule(key);

        for (int section = 0; section < 16; section++) {
            IncrementalChunkSnapshotScheduler.CaptureStep step = scheduler.peekNextSection();
            assertNotNull(step);
            assertEquals(section, step.getSectionIndex());
            IncrementalChunkSnapshotScheduler.CaptureAdvance advance =
                scheduler.sectionCaptured(step);
            assertEquals(section == 15, advance.isComplete());
        }

        assertNull(scheduler.peekNextSection());
        assertEquals(0, scheduler.getPendingSectionCount());
    }

    @Test
    public void pendingCapacityLeavesNewestLoadExplicitlyPartialAndUnscheduled() {
        IncrementalChunkSnapshotScheduler scheduler = new IncrementalChunkSnapshotScheduler(1);
        scheduler.schedule(new ChunkSnapshotKey(0, 0, 0));

        IncrementalChunkSnapshotScheduler.ScheduleResult dropped =
            scheduler.schedule(new ChunkSnapshotKey(0, 1, 0));

        assertEquals(
            IncrementalChunkSnapshotScheduler.ScheduleOutcome.PENDING_CAPACITY_EXCEEDED,
            dropped.getOutcome()
        );
        assertEquals(IncrementalChunkSnapshotScheduler.CaptureStatus.PARTIAL,
            dropped.getStatus());
        assertEquals(1, scheduler.getPendingChunkCount());
    }

    @Test
    public void defaultCapacityAcceptsObservedNaturalLoadBurstWithoutCoverageLoss() {
        IncrementalChunkSnapshotScheduler scheduler =
            new IncrementalChunkSnapshotScheduler(
                ClientChunkSnapshotCollector.DEFAULT_PENDING_CHUNK_CAPACITY
            );

        for (int chunk = 0; chunk < 625; chunk++) {
            IncrementalChunkSnapshotScheduler.ScheduleResult result = scheduler.schedule(
                new ChunkSnapshotKey(0, chunk % 25, chunk / 25)
            );
            assertEquals(
                IncrementalChunkSnapshotScheduler.ScheduleOutcome.SCHEDULED,
                result.getOutcome()
            );
        }

        assertEquals(625, scheduler.getPendingChunkCount());
        assertEquals(625 * ChunkSectionSnapshot.SECTIONS_PER_CHUNK,
            scheduler.getPendingSectionCount());
    }

    @Test
    public void exceedingDefaultCapacityIsExplicitAndCannotBecomeComplete() {
        int capacity = ClientChunkSnapshotCollector.DEFAULT_PENDING_CHUNK_CAPACITY;
        IncrementalChunkSnapshotScheduler scheduler =
            new IncrementalChunkSnapshotScheduler(capacity);
        for (int chunk = 0; chunk < capacity; chunk++) {
            assertTrue(scheduler.schedule(new ChunkSnapshotKey(0, chunk, 0)).isScheduled());
        }
        ChunkSnapshotKey overflowKey = new ChunkSnapshotKey(0, capacity, 0);
        IncrementalChunkSnapshotScheduler.ScheduleResult overflow =
            scheduler.schedule(overflowKey);

        assertEquals(
            IncrementalChunkSnapshotScheduler.ScheduleOutcome.PENDING_CAPACITY_EXCEEDED,
            overflow.getOutcome()
        );
        assertEquals(IncrementalChunkSnapshotScheduler.CaptureStatus.PARTIAL,
            overflow.getStatus());

        IncrementalChunkSnapshotScheduler.CaptureStep step = scheduler.peekNextSection();
        while (step != null) {
            scheduler.sectionCaptured(step);
            step = scheduler.peekNextSection();
        }

        IncrementalChunkSnapshotScheduler.ScheduleResult repeatedOverflow =
            scheduler.schedule(overflowKey);
        assertEquals(IncrementalChunkSnapshotScheduler.ScheduleOutcome.DUPLICATE,
            repeatedOverflow.getOutcome());
        assertEquals(IncrementalChunkSnapshotScheduler.CaptureStatus.PARTIAL,
            repeatedOverflow.getStatus());
        assertEquals(0, repeatedOverflow.getCapturedSections());
    }
}
