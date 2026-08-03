package com.bedwarsbot.observation;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class BoundedObservationQueue {
    private final int capacity;
    private final ArrayBlockingQueue<ObservationEvent> queue;
    private final AtomicLong acceptedEvents = new AtomicLong();
    private final AtomicLong droppedEvents = new AtomicLong();

    public BoundedObservationQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<ObservationEvent>(capacity);
    }

    public boolean offer(ObservationEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (queue.offer(event)) {
            acceptedEvents.incrementAndGet();
            return true;
        }
        droppedEvents.incrementAndGet();
        return false;
    }

    public ObservationEvent poll() {
        return queue.poll();
    }

    public ObservationEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    public int getCapacity() {
        return capacity;
    }

    public int getDepth() {
        return queue.size();
    }

    public long getAcceptedEvents() {
        return acceptedEvents.get();
    }

    public long getDroppedEvents() {
        return droppedEvents.get();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
