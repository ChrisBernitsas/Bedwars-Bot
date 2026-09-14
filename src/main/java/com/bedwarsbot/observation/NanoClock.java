package com.bedwarsbot.observation;

public interface NanoClock {
    NanoClock SYSTEM = new NanoClock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    };

    long nanoTime();
}
