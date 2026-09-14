package com.bedwarsbot.observation;

import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ChunkSnapshotEventSerializer {
    public String eventType(ChunkSnapshotEvent event) {
        switch (event.getType()) {
            case SCHEDULED:
                return "chunk_snapshot_scheduled";
            case SECTION_CAPTURED:
                return "chunk_snapshot_section_captured";
            case COMPLETED:
                return "chunk_snapshot_completed";
            case ABORTED:
                return "chunk_snapshot_aborted";
            case UNLOADED:
                return "chunk_snapshot_unloaded";
            case DIMENSION_UNLOADED:
                return "chunk_snapshot_dimension_unloaded";
            case BLOCK_STATE_OBSERVED:
                return "chunk_snapshot_block_reconciled";
            case SCAN_TICK:
                return "chunk_snapshot_scan_tick";
            default:
                throw new IllegalStateException("Unhandled snapshot event " + event.getType());
        }
    }

    public Map<String, String> details(
        ChunkSnapshotEvent event,
        ClientObservedChunkStore.ApplyResult result,
        ClientObservedChunkStore.StoreSnapshot store
    ) {
        if (event == null || result == null || store == null) {
            throw new IllegalArgumentException("snapshot serialization inputs must not be null");
        }
        Map<String, String> details = new LinkedHashMap<String, String>();
        ChunkSnapshotKey key = event.getChunkKey();
        details.put("capture_monotonic_nanos", Long.toString(event.getCapturedNanos()));
        details.put("chunk_x", Integer.toString(key.getChunkX()));
        details.put("chunk_z", Integer.toString(key.getChunkZ()));
        details.put("dimension", Integer.toString(key.getDimension()));
        details.put("load_generation", Long.toString(event.getLoadGeneration()));
        details.put("snapshot_capture_sequence", Long.toString(event.getCaptureSequence()));
        details.put("snapshot_schema_version", Integer.toString(ChunkSnapshotEvent.SCHEMA_VERSION));
        details.put("snapshot_type", event.getType().name());
        details.put("apply_outcome", result.getOutcome().name());
        details.put("coverage_status", result.getStatus() == null
            ? "UNKNOWN"
            : result.getStatus().name());
        details.put("covered_sections", Integer.toString(result.getCoveredSections()));
        details.put("captured_sections", Integer.toString(event.getCapturedSections()));
        if (!event.getOutcome().isEmpty()) {
            details.put("capture_outcome", event.getOutcome());
        }
        if (!event.getReason().isEmpty()) {
            details.put("reason", event.getReason());
        }
        if (event.getSectionSnapshot() != null) {
            appendSection(details, event.getSectionSnapshot());
        }
        if (event.getType() == ChunkSnapshotEvent.Type.SCAN_TICK) {
            details.put("blocks_copied_this_tick", Integer.toString(
                event.getBlocksCopiedThisTick()
            ));
            details.put("scan_elapsed_nanos_this_tick", Long.toString(
                event.getScanElapsedNanos()
            ));
            details.put("scan_stop_reason", event.getScanStopReason().name());
            details.put("block_budget_stopped_work", Boolean.toString(
                event.getScanStopReason() == IncrementalScanBudget.StopReason.BLOCK_BUDGET
            ));
            details.put("time_budget_stopped_work", Boolean.toString(
                event.getScanStopReason() == IncrementalScanBudget.StopReason.TIME_BUDGET
            ));
            details.put("max_observed_client_scan_nanos", Long.toString(
                event.getMaxObservedScanNanos()
            ));
            details.put("partial_section_resume_count", Long.toString(
                event.getPartialSectionResumeCount()
            ));
        }
        details.put("store_complete_chunks", Integer.toString(store.getCompleteChunks()));
        details.put("store_partial_chunks", Integer.toString(store.getPartialChunks()));
        details.put("store_stale_chunks", Integer.toString(store.getStaleChunks()));
        details.put("store_tracked_chunks", Integer.toString(store.getTrackedChunks()));
        return Collections.unmodifiableMap(details);
    }

    private static void appendSection(
        Map<String, String> details,
        ChunkSectionSnapshot section
    ) {
        details.put("coverage_blocks", Integer.toString(ChunkSectionSnapshot.BLOCKS_PER_SECTION));
        details.put("section_index", Integer.toString(section.getSectionIndex()));
        details.put("section_y", Integer.toString(section.getSectionIndex() * 16));

        int firstState = section.getStateId(0);
        int nonAirBlocks = firstState == ChunkSectionSnapshot.AIR_STATE_ID ? 0 : 1;
        boolean uniform = true;
        for (int index = 1; index < ChunkSectionSnapshot.BLOCKS_PER_SECTION; index++) {
            int stateId = section.getStateId(index);
            if (stateId != firstState) {
                uniform = false;
            }
            if (stateId != ChunkSectionSnapshot.AIR_STATE_ID) {
                nonAirBlocks++;
            }
        }
        details.put("non_air_blocks", Integer.toString(nonAirBlocks));
        if (uniform) {
            details.put("state_encoding", "uniform_u16_v1");
            details.put("state_data", Integer.toString(firstState));
            return;
        }

        byte[] bytes = new byte[ChunkSectionSnapshot.BLOCKS_PER_SECTION * 2];
        for (int index = 0; index < ChunkSectionSnapshot.BLOCKS_PER_SECTION; index++) {
            int stateId = section.getStateId(index);
            bytes[index * 2] = (byte) (stateId & 255);
            bytes[index * 2 + 1] = (byte) (stateId >>> 8 & 255);
        }
        details.put("state_encoding", "u16le_base64_v1");
        details.put("state_data", Base64.getEncoder().encodeToString(bytes));
        details.put("state_byte_order", "little_endian");
        details.put("state_layout", "local_y_z_x");
    }
}
