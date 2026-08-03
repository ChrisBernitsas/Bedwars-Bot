package com.bedwarsbot.logging;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public final class LogRecord {
    public static final int SCHEMA_VERSION = 1;

    private final String sessionId;
    private final long sequence;
    private final long clientTick;
    private final Long worldTick;
    private final long monotonicNanos;
    private final String wallTimeUtc;
    private final String sourceThread;
    private final String component;
    private final String eventType;
    private final SortedMap<String, String> details;

    public LogRecord(
        String sessionId,
        long sequence,
        long clientTick,
        Long worldTick,
        long monotonicNanos,
        String wallTimeUtc,
        String sourceThread,
        String component,
        String eventType,
        Map<String, String> details
    ) {
        this.sessionId = requireText(sessionId, "sessionId");
        this.sequence = sequence;
        this.clientTick = clientTick;
        this.worldTick = worldTick;
        this.monotonicNanos = monotonicNanos;
        this.wallTimeUtc = requireText(wallTimeUtc, "wallTimeUtc");
        this.sourceThread = requireText(sourceThread, "sourceThread");
        this.component = requireText(component, "component");
        this.eventType = requireText(eventType, "eventType");
        TreeMap<String, String> sortedDetails = new TreeMap<String, String>();
        if (details != null) {
            sortedDetails.putAll(details);
        }
        this.details = Collections.unmodifiableSortedMap(sortedDetails);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getSequence() {
        return sequence;
    }

    public long getClientTick() {
        return clientTick;
    }

    public Long getWorldTick() {
        return worldTick;
    }

    public long getMonotonicNanos() {
        return monotonicNanos;
    }

    public String getWallTimeUtc() {
        return wallTimeUtc;
    }

    public String getSourceThread() {
        return sourceThread;
    }

    public String getComponent() {
        return component;
    }

    public String getEventType() {
        return eventType;
    }

    public SortedMap<String, String> getDetails() {
        return details;
    }
}
