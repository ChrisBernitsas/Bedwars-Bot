package com.bedwarsbot.observation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BoundedObservationQueueTest {
    @Test
    public void preservesFifoOrderAndDropsNewestEventWhenFull() {
        BoundedObservationQueue queue = new BoundedObservationQueue(2);
        ObservationEvent first = chunk(1L);
        ObservationEvent second = chunk(2L);
        ObservationEvent dropped = chunk(3L);

        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));
        assertFalse(queue.offer(dropped));

        assertEquals(2, queue.getDepth());
        assertEquals(2L, queue.getAcceptedEvents());
        assertEquals(1L, queue.getDroppedEvents());
        assertEquals(1L, queue.poll().getSequence());
        assertEquals(2L, queue.poll().getSequence());
        assertTrue(queue.isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveCapacity() {
        new BoundedObservationQueue(0);
    }

    private static ObservationEvent chunk(long sequence) {
        return ObservationEvent.chunkLoaded(sequence, 2L, 3L, 4L, 0, 1, 1);
    }
}
