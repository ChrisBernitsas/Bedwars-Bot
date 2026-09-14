package com.bedwarsbot.world.canonical;

final class CanonicalMapLimits {
    static final long MAX_JSON_FILE_BYTES = 4L * 1024L * 1024L;
    static final long MAX_TOTAL_MAP_BYTES = 64L * 1024L * 1024L;
    static final int MAX_GEOMETRY_FILES = 4096;
    static final int MAX_SECTIONS_PER_CHUNK = 16;
    static final int MAX_PALETTE_ENTRIES = CanonicalSection.POSITION_COUNT;
    static final int MAX_LANDMARKS = 16_384;
    static final int MAX_JSON_NESTING_DEPTH = 64;
    static final int MAX_JSON_VALUES = 500_000;
    static final int MAX_JSON_CONTAINER_ENTRIES = 100_000;

    private CanonicalMapLimits() {
    }
}
