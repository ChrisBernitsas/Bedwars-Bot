package com.bedwarsbot.observation;

import java.util.Map;

import com.bedwarsbot.logging.JsonLineEncoder;
import com.bedwarsbot.logging.LogRecord;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ObservationEventSerializerTest {
    @Test
    public void serializesSchemaVersionedObservationAndOverlayDetails() {
        ObservationEvent event = ObservationEvent.blockState(
            7L,
            11L,
            13L,
            17L,
            new BlockPosition(0, -2, 64, 31),
            new BlockStateSnapshot(35, "minecraft:wool", 14)
        );
        SparseBlockOverlay overlay = new SparseBlockOverlay();
        SparseBlockOverlay.ApplyResult result = overlay.apply(event);
        ObservationEventSerializer serializer = new ObservationEventSerializer();

        Map<String, String> observation = serializer.observationDetails(event);
        Map<String, String> overlayDetails = serializer.overlayDetails(result, overlay.snapshot());

        assertEquals("1", observation.get("observation_schema_version"));
        assertEquals("7", observation.get("observation_sequence"));
        assertEquals("minecraft:wool", observation.get("block_registry_name"));
        assertEquals("14", observation.get("block_metadata"));
        assertEquals("ADDED", overlayDetails.get("outcome"));
        assertEquals("UNKNOWN", overlayDetails.get("previous_availability"));
        assertEquals("KNOWN", overlayDetails.get("current_availability"));

        LogRecord record = new LogRecord(
            "session",
            1L,
            event.getClientTick(),
            event.getWorldTick(),
            19L,
            "2026-08-03T00:00:00Z",
            "bedwarsbot-observation-worker",
            "observation",
            serializer.observationEventType(event),
            observation
        );
        String json = new JsonLineEncoder().encode(record);
        assertTrue(json.contains("\"schema_version\":1"));
        assertTrue(json.contains("\"observation_schema_version\":\"1\""));
        assertTrue(json.contains("\"event_type\":\"block_state_observed\""));
    }
}
