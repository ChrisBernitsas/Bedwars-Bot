package com.bedwarsbot.verification;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bedwarsbot.control.BotMode;
import com.bedwarsbot.control.InputFrame;
import com.bedwarsbot.logging.AsyncSessionLogger;
import com.bedwarsbot.observation.ObservationHudSnapshot;
import com.bedwarsbot.observation.SparseBlockOverlay;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class VerificationEventLoggerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesHarmlessMarkerAndObservationHealthSummary() throws Exception {
        Path logDirectory = temporaryFolder.newFolder("logs").toPath();
        AsyncSessionLogger sessionLogger = new AsyncSessionLogger(
            logDirectory,
            16,
            "verification-test"
        );
        VerificationEventLogger verificationLogger = new VerificationEventLogger(sessionLogger);
        Map<String, String> copiedContext = new LinkedHashMap<String, String>();
        copiedContext.put("crosshair_target_type", "BLOCK");
        copiedContext.put("held_item_available", "true");
        copiedContext.put("held_item_metadata", "14");
        copiedContext.put("held_item_registry_name", "minecraft:wool");
        copiedContext.put("player_available", "true");
        copiedContext.put("player_block_x", "0");
        copiedContext.put("player_block_y", "64");
        copiedContext.put("player_block_z", "0");
        copiedContext.put("player_dimension", "0");
        copiedContext.put("player_pitch", "12.5");
        copiedContext.put("player_x", "0.25");
        copiedContext.put("player_y", "64.0");
        copiedContext.put("player_yaw", "90.0");
        copiedContext.put("player_z", "0.75");
        copiedContext.put("target_block_available", "true");
        copiedContext.put("target_block_dimension", "0");
        copiedContext.put("target_block_id", "35");
        copiedContext.put("target_block_metadata", "14");
        copiedContext.put("target_block_registry_name", "minecraft:wool");
        copiedContext.put("target_block_x", "1");
        copiedContext.put("target_block_y", "64");
        copiedContext.put("target_block_z", "2");

        assertTrue(verificationLogger.logMarker(
            "before manual block change",
            12L,
            34L,
            BotMode.DISABLED,
            InputFrame.neutral(),
            InputFrame.neutral(),
            new VerificationMarkerContext(copiedContext)
        ));
        ObservationHudSnapshot observationSnapshot = new ObservationHudSnapshot(
            new SparseBlockOverlay().snapshot(),
            0,
            4096,
            7L,
            0L,
            7L,
            0L,
            100L,
            80L,
            150L,
            null
        );
        assertTrue(verificationLogger.logObservationPipelineSummary(observationSnapshot));
        sessionLogger.close();

        List<String> lines = Files.readAllLines(
            sessionLogger.getLogFile(),
            StandardCharsets.UTF_8
        );
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("\"event_type\":\"verification_marker\""));
        assertTrue(lines.get(0).contains("\"label\":\"before manual block change\""));
        assertTrue(lines.get(0).contains("\"mode\":\"DISABLED\""));
        assertTrue(lines.get(0).contains("\"crosshair_target_type\":\"BLOCK\""));
        assertTrue(lines.get(0).contains("\"held_item_registry_name\":\"minecraft:wool\""));
        assertTrue(lines.get(0).contains("\"player_x\":\"0.25\""));
        assertTrue(lines.get(0).contains("\"player_yaw\":\"90.0\""));
        assertTrue(lines.get(0).contains("\"target_block_metadata\":\"14\""));
        assertTrue(lines.get(0).contains("\"target_block_registry_name\":\"minecraft:wool\""));
        assertTrue(lines.get(0).contains("\"target_block_x\":\"1\""));
        assertTrue(lines.get(1).contains("\"event_type\":\"observation_pipeline_summary\""));
        assertTrue(lines.get(1).contains("\"dropped_events\":\"0\""));
        assertTrue(lines.get(1).contains("\"failure_count\":\"0\""));
        assertTrue(lines.get(2).contains("\"event_type\":\"session_end\""));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyMarkerLabel() {
        Path unused = temporaryFolder.getRoot().toPath();
        AsyncSessionLogger sessionLogger = new AsyncSessionLogger(unused, 4, "invalid-label");
        try {
            new VerificationEventLogger(sessionLogger).logMarker(
                "",
                0L,
                null,
                BotMode.DISABLED,
                InputFrame.neutral(),
                InputFrame.neutral(),
                VerificationMarkerContext.empty()
            );
        } finally {
            sessionLogger.close();
        }
    }
}
