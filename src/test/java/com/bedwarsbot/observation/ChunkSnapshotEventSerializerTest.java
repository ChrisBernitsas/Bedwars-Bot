package com.bedwarsbot.observation;

import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ChunkSnapshotEventSerializerTest {
    @Test
    public void serializesUniformAirAndDenseSectionsWithSchemaAndCoverage() {
        ClientObservedChunkStore store = new ClientObservedChunkStore();
        ChunkSnapshotKey key = new ChunkSnapshotKey(0, 0, 0);
        store.apply(ChunkSnapshotEvent.scheduled(
            1L, 2L, 3L, 4L, key, 1L, "SCHEDULED"
        ));
        ChunkSnapshotEventSerializer serializer = new ChunkSnapshotEventSerializer();

        ChunkSnapshotEvent air = ChunkSnapshotEvent.sectionCaptured(
            2L, 2L, 3L, 4L, key, 1L, ChunkSectionSnapshot.uniform(0, 0), 1
        );
        ClientObservedChunkStore.ApplyResult airResult = store.apply(air);
        Map<String, String> airDetails = serializer.details(air, airResult, store.snapshot());
        assertEquals("1", airDetails.get("snapshot_schema_version"));
        assertEquals("4096", airDetails.get("coverage_blocks"));
        assertEquals("uniform_u16_v1", airDetails.get("state_encoding"));
        assertEquals("0", airDetails.get("state_data"));

        char[] states = new char[4096];
        states[0] = 35;
        ChunkSnapshotEvent dense = ChunkSnapshotEvent.sectionCaptured(
            3L, 2L, 3L, 4L, key, 1L, ChunkSectionSnapshot.copyDense(1, states), 2
        );
        ClientObservedChunkStore.ApplyResult denseResult = store.apply(dense);
        Map<String, String> denseDetails = serializer.details(
            dense, denseResult, store.snapshot()
        );
        assertEquals("u16le_base64_v1", denseDetails.get("state_encoding"));
        assertEquals("1", denseDetails.get("non_air_blocks"));
        assertTrue(denseDetails.get("state_data").length() > 1000);
        assertEquals("chunk_snapshot_section_captured", serializer.eventType(dense));
    }

    @Test
    public void serializesPerTickScanBudgetMetrics() {
        ClientObservedChunkStore store = new ClientObservedChunkStore();
        ChunkSnapshotKey key = new ChunkSnapshotKey(0, 2, 3);
        store.apply(ChunkSnapshotEvent.scheduled(
            1L, 2L, 3L, 4L, key, 9L, "SCHEDULED"
        ));
        ChunkSnapshotEvent event = ChunkSnapshotEvent.scanTick(
            2L,
            10L,
            11L,
            12L,
            key,
            9L,
            731,
            1_500_010L,
            IncrementalScanBudget.StopReason.TIME_BUDGET,
            1_500_010L,
            4L
        );
        ClientObservedChunkStore.ApplyResult result = store.apply(event);
        ChunkSnapshotEventSerializer serializer = new ChunkSnapshotEventSerializer();
        Map<String, String> details = serializer.details(event, result, store.snapshot());

        assertEquals("chunk_snapshot_scan_tick", serializer.eventType(event));
        assertEquals("731", details.get("blocks_copied_this_tick"));
        assertEquals("1500010", details.get("scan_elapsed_nanos_this_tick"));
        assertEquals("TIME_BUDGET", details.get("scan_stop_reason"));
        assertEquals("false", details.get("block_budget_stopped_work"));
        assertEquals("true", details.get("time_budget_stopped_work"));
        assertEquals("4", details.get("partial_section_resume_count"));
    }
}
