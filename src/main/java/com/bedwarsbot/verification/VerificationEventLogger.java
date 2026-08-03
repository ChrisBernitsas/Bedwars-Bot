package com.bedwarsbot.verification;

import java.util.LinkedHashMap;
import java.util.Map;

import com.bedwarsbot.control.BotMode;
import com.bedwarsbot.control.InputFrame;
import com.bedwarsbot.logging.AsyncSessionLogger;
import com.bedwarsbot.observation.ObservationEvent;
import com.bedwarsbot.observation.ObservationHudSnapshot;
import com.bedwarsbot.observation.SparseBlockOverlay;

public final class VerificationEventLogger {
    public static final int MAX_LABEL_LENGTH = 80;

    private final AsyncSessionLogger sessionLogger;

    public VerificationEventLogger(AsyncSessionLogger sessionLogger) {
        if (sessionLogger == null) {
            throw new IllegalArgumentException("sessionLogger must not be null");
        }
        this.sessionLogger = sessionLogger;
    }

    public boolean logMarker(
        String label,
        long clientTick,
        Long worldTick,
        BotMode mode,
        InputFrame proposedFrame,
        InputFrame activeFrame,
        VerificationMarkerContext context
    ) {
        requireMarkerLabel(label);
        if (mode == null || proposedFrame == null || activeFrame == null || context == null) {
            throw new IllegalArgumentException("marker state must not be null");
        }
        Map<String, String> details = new LinkedHashMap<String, String>();
        details.put("active", activeFrame.toCompactString());
        details.put("label", label);
        details.put("mode", mode.name());
        details.put("proposed", proposedFrame.toCompactString());
        details.putAll(context.getDetails());
        return sessionLogger.tryLog(
            "verification",
            "verification_marker",
            clientTick,
            worldTick,
            details
        );
    }

    public boolean logObservationPipelineSummary(ObservationHudSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        SparseBlockOverlay.Snapshot overlay = snapshot.getOverlaySnapshot();
        Map<String, String> details = new LinkedHashMap<String, String>();
        details.put("accepted_events", Long.toString(snapshot.getAcceptedEvents()));
        details.put("average_processing_nanos", Long.toString(snapshot.getAverageProcessingNanos()));
        details.put("dropped_events", Long.toString(snapshot.getDroppedEvents()));
        details.put("failure_count", Long.toString(snapshot.getFailureCount()));
        details.put("failure_message", nullToEmpty(snapshot.getFailureMessage()));
        details.put("last_processing_nanos", Long.toString(snapshot.getLastProcessingNanos()));
        details.put("max_processing_nanos", Long.toString(snapshot.getMaxProcessingNanos()));
        details.put("observation_schema_version", Integer.toString(ObservationEvent.SCHEMA_VERSION));
        details.put("overlay_known", Integer.toString(overlay.getKnownCount()));
        details.put("overlay_size", Integer.toString(overlay.getOverlaySize()));
        details.put("overlay_stale", Integer.toString(overlay.getStaleCount()));
        details.put("processed_events", Long.toString(snapshot.getProcessedEvents()));
        details.put("queue_capacity", Integer.toString(snapshot.getQueueCapacity()));
        details.put("queue_depth", Integer.toString(snapshot.getQueueDepth()));
        return sessionLogger.tryLog(
            "verification",
            "observation_pipeline_summary",
            -1L,
            null,
            details
        );
    }

    private static void requireMarkerLabel(String label) {
        if (label == null || label.isEmpty()) {
            throw new IllegalArgumentException("marker label must not be empty");
        }
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("marker label is too long");
        }
        for (int index = 0; index < label.length(); index++) {
            if (Character.isISOControl(label.charAt(index))) {
                throw new IllegalArgumentException("marker label contains a control character");
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
