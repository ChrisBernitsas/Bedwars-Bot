package com.bedwarsbot.logging;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class JsonLineEncoderTest {
    @Test
    public void encodesStableSchemaAndEscapesText() {
        Map<String, String> details = new LinkedHashMap<String, String>();
        details.put("zeta", "line\none");
        details.put("alpha", "quote\"");
        LogRecord record = new LogRecord(
            "session-1",
            7L,
            42L,
            null,
            1234L,
            "2026-08-03T01:00:00Z",
            "Client thread",
            "safety_gate",
            "input_decision",
            details
        );

        String encoded = new JsonLineEncoder().encode(record);

        assertEquals(
            "{\"schema_version\":1,\"session_id\":\"session-1\",\"sequence\":7,"
                + "\"client_tick\":42,\"world_tick\":null,\"monotonic_nanos\":1234,"
                + "\"wall_time_utc\":\"2026-08-03T01:00:00Z\","
                + "\"source_thread\":\"Client thread\",\"component\":\"safety_gate\","
                + "\"event_type\":\"input_decision\",\"details\":{"
                + "\"alpha\":\"quote\\\"\",\"zeta\":\"line\\none\"}}",
            encoded
        );
    }
}
