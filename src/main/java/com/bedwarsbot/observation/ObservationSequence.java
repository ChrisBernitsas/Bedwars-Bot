package com.bedwarsbot.observation;

import java.util.concurrent.atomic.AtomicLong;

public final class ObservationSequence {
    private final AtomicLong nextSequence = new AtomicLong();

    public long next() {
        return nextSequence.getAndIncrement();
    }
}
