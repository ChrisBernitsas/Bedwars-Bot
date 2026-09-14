package com.bedwarsbot.observation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BoundedChunkSnapshotQueueTest {
    @Test
    public void preservesFifoAndDropsNewestWhenFull() {
        BoundedChunkSnapshotQueue queue = new BoundedChunkSnapshotQueue(2);
        ChunkSnapshotEvent first = scheduled(1L, 1L);
        ChunkSnapshotEvent second = scheduled(2L, 2L);
        ChunkSnapshotEvent dropped = scheduled(3L, 3L);

        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));
        assertFalse(queue.offer(dropped));
        assertEquals(2, queue.getDepth());
        assertEquals(2L, queue.getAcceptedEvents());
        assertEquals(1L, queue.getDroppedEvents());
        assertEquals(1L, queue.poll().getCaptureSequence());
        assertEquals(2L, queue.poll().getCaptureSequence());
        assertTrue(queue.isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveCapacity() {
        new BoundedChunkSnapshotQueue(0);
    }

    private static ChunkSnapshotEvent scheduled(long sequence, long generation) {
        return ChunkSnapshotEvent.scheduled(
            sequence,
            2L,
            3L,
            4L,
            new ChunkSnapshotKey(0, 0, 0),
            generation,
            "SCHEDULED"
        );
    }
}
