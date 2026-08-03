package com.bedwarsbot.logging;

import java.util.Map;

public final class JsonLineEncoder {
    public String encode(LogRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }

        StringBuilder json = new StringBuilder(384);
        json.append('{');
        appendNumber(json, "schema_version", LogRecord.SCHEMA_VERSION);
        appendString(json, "session_id", record.getSessionId());
        appendNumber(json, "sequence", record.getSequence());
        appendNumber(json, "client_tick", record.getClientTick());
        appendNullableNumber(json, "world_tick", record.getWorldTick());
        appendNumber(json, "monotonic_nanos", record.getMonotonicNanos());
        appendString(json, "wall_time_utc", record.getWallTimeUtc());
        appendString(json, "source_thread", record.getSourceThread());
        appendString(json, "component", record.getComponent());
        appendString(json, "event_type", record.getEventType());
        appendDetails(json, record.getDetails());
        json.append('}');
        return json.toString();
    }

    private static void appendNumber(StringBuilder json, String name, long value) {
        appendSeparator(json);
        appendQuoted(json, name);
        json.append(':').append(value);
    }

    private static void appendNullableNumber(StringBuilder json, String name, Long value) {
        appendSeparator(json);
        appendQuoted(json, name);
        json.append(':');
        if (value == null) {
            json.append("null");
        } else {
            json.append(value.longValue());
        }
    }

    private static void appendString(StringBuilder json, String name, String value) {
        appendSeparator(json);
        appendQuoted(json, name);
        json.append(':');
        appendQuoted(json, value);
    }

    private static void appendDetails(StringBuilder json, Map<String, String> details) {
        appendSeparator(json);
        appendQuoted(json, "details");
        json.append(':').append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : details.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            appendQuoted(json, entry.getKey());
            json.append(':');
            if (entry.getValue() == null) {
                json.append("null");
            } else {
                appendQuoted(json, entry.getValue());
            }
        }
        json.append('}');
    }

    private static void appendSeparator(StringBuilder json) {
        if (json.length() > 1) {
            json.append(',');
        }
    }

    private static void appendQuoted(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    json.append("\\\"");
                    break;
                case '\\':
                    json.append("\\\\");
                    break;
                case '\b':
                    json.append("\\b");
                    break;
                case '\f':
                    json.append("\\f");
                    break;
                case '\n':
                    json.append("\\n");
                    break;
                case '\r':
                    json.append("\\r");
                    break;
                case '\t':
                    json.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        appendUnicodeEscape(json, character);
                    } else {
                        json.append(character);
                    }
            }
        }
        json.append('"');
    }

    private static void appendUnicodeEscape(StringBuilder json, char character) {
        String hex = Integer.toHexString(character);
        json.append("\\u");
        for (int index = hex.length(); index < 4; index++) {
            json.append('0');
        }
        json.append(hex);
    }
}
