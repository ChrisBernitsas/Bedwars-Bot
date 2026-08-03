package com.bedwarsbot.observation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ObservationEventSerializer {
    public Map<String, String> observationDetails(ObservationEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        Map<String, String> details = baseDetails(event);
        details.put("observation_type", event.getType().name());
        appendPositionAndState(details, event);
        return Collections.unmodifiableMap(details);
    }

    public Map<String, String> overlayDetails(
        SparseBlockOverlay.ApplyResult result,
        SparseBlockOverlay.Snapshot snapshot
    ) {
        if (result == null || snapshot == null) {
            throw new IllegalArgumentException("result and snapshot must not be null");
        }
        ObservationEvent event = result.getEvent();
        Map<String, String> details = baseDetails(event);
        details.put("affected_entries", Integer.toString(result.getAffectedEntries()));
        details.put("outcome", result.getOutcome().name());
        details.put("overlay_known", Integer.toString(snapshot.getKnownCount()));
        details.put("overlay_size", Integer.toString(snapshot.getOverlaySize()));
        details.put("overlay_stale", Integer.toString(snapshot.getStaleCount()));
        appendPositionAndState(details, event);
        appendOverlayValue(details, "previous", result.getPreviousValue());
        appendOverlayValue(details, "current", result.getCurrentValue());
        return Collections.unmodifiableMap(details);
    }

    public String observationEventType(ObservationEvent event) {
        switch (event.getType()) {
            case BLOCK_STATE:
                return "block_state_observed";
            case BLOCK_UNAVAILABLE:
                return "block_state_unavailable";
            case CHUNK_LOADED:
                return "chunk_loaded_observed";
            case CHUNK_UNLOADED:
                return "chunk_unloaded_observed";
            case DIMENSION_UNLOADED:
                return "dimension_unloaded_observed";
            default:
                throw new IllegalStateException("Unhandled observation type " + event.getType());
        }
    }

    public String overlayEventType(SparseBlockOverlay.ApplyResult result) {
        return "overlay_" + result.getOutcome().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static Map<String, String> baseDetails(ObservationEvent event) {
        Map<String, String> details = new LinkedHashMap<String, String>();
        details.put("capture_monotonic_nanos", Long.toString(event.getCapturedNanos()));
        details.put("chunk_x", Integer.toString(event.getChunkX()));
        details.put("chunk_z", Integer.toString(event.getChunkZ()));
        details.put("dimension", Integer.toString(event.getDimension()));
        details.put("observation_schema_version", Integer.toString(ObservationEvent.SCHEMA_VERSION));
        details.put("observation_sequence", Long.toString(event.getSequence()));
        return details;
    }

    private static void appendPositionAndState(
        Map<String, String> details,
        ObservationEvent event
    ) {
        BlockPosition position = event.getPosition();
        if (position != null) {
            details.put("x", Integer.toString(position.getX()));
            details.put("y", Integer.toString(position.getY()));
            details.put("z", Integer.toString(position.getZ()));
        }
        BlockStateSnapshot state = event.getBlockState();
        if (state != null) {
            details.put("block_id", Integer.toString(state.getBlockId()));
            details.put("block_metadata", Integer.toString(state.getMetadata()));
            details.put("block_registry_name", state.getRegistryName());
        }
    }

    private static void appendOverlayValue(
        Map<String, String> details,
        String prefix,
        SparseBlockOverlay.OverlayValue value
    ) {
        details.put(prefix + "_availability", value.getAvailability().name());
        BlockStateSnapshot state = value.getBlockState();
        if (state != null) {
            details.put(prefix + "_block_id", Integer.toString(state.getBlockId()));
            details.put(prefix + "_block_metadata", Integer.toString(state.getMetadata()));
            details.put(prefix + "_block_registry_name", state.getRegistryName());
            details.put(prefix + "_sequence", Long.toString(value.getLastSequence()));
        }
    }
}
