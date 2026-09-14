package com.bedwarsbot.observation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class BoundedChunkSnapshotQueue {
    private final int capacity;
    private final Deque<ChunkSnapshotEvent> events = new ArrayDeque<ChunkSnapshotEvent>();
    private final AtomicLong acceptedEvents = new AtomicLong();
    private final AtomicLong droppedEvents = new AtomicLong();

    public BoundedChunkSnapshotQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized boolean offer(ChunkSnapshotEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (events.size() >= capacity) {
            droppedEvents.incrementAndGet();
            return false;
        }
        events.addLast(event);
        acceptedEvents.incrementAndGet();
        notifyAll();
        return true;
    }

    public synchronized ChunkSnapshotEvent poll() {
        return events.pollFirst();
    }

    public synchronized ChunkSnapshotEvent poll(long timeout, TimeUnit unit)
        throws InterruptedException {
        if (unit == null) {
            throw new IllegalArgumentException("unit must not be null");
        }
        long remainingNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos;
        while (events.isEmpty() && remainingNanos > 0L) {
            TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
            remainingNanos = deadline - System.nanoTime();
        }
        return events.pollFirst();
    }

    public synchronized int getDepth() {
        return events.size();
    }

    public int getCapacity() {
        return capacity;
    }

    public long getAcceptedEvents() {
        return acceptedEvents.get();
    }

    public long getDroppedEvents() {
        return droppedEvents.get();
    }

    public synchronized boolean isEmpty() {
        return events.isEmpty();
    }
}
