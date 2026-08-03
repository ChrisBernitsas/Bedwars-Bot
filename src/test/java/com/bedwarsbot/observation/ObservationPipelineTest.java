package com.bedwarsbot.observation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.bedwarsbot.logging.AsyncSessionLogger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ObservationPipelineTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void drainsEventsInOrderAndLogsObservationAndOverlayRecords() throws Exception {
        Path logDirectory = temporaryFolder.newFolder("logs").toPath();
        AsyncSessionLogger logger = new AsyncSessionLogger(logDirectory, 64, "observation-test");
        ObservationPipeline pipeline = new ObservationPipeline(logger, 8);
        BlockPosition position = new BlockPosition(0, 1, 64, 1);
        BlockStateSnapshot state = new BlockStateSnapshot(1, "minecraft:stone", 0);

        assertTrue(pipeline.tryCapture(ObservationEvent.chunkLoaded(1L, 2L, 3L, 4L, 0, 0, 0)));
        assertTrue(pipeline.tryCapture(ObservationEvent.blockState(2L, 3L, 4L, 5L, position, state)));
        assertTrue(pipeline.tryCapture(ObservationEvent.chunkUnloaded(3L, 4L, 5L, 6L, 0, 0, 0)));
        assertTrue(pipeline.tryCapture(ObservationEvent.chunkLoaded(4L, 5L, 6L, 7L, 0, 0, 0)));
        assertTrue(pipeline.tryCapture(ObservationEvent.blockState(5L, 6L, 7L, 8L, position, state)));

        pipeline.close();
        ObservationHudSnapshot snapshot = pipeline.getHudSnapshotReference().get();
        assertEquals(5L, snapshot.getProcessedEvents());
        assertEquals(0L, snapshot.getDroppedEvents());
        assertEquals(0, snapshot.getQueueDepth());
        assertEquals(SparseBlockOverlay.Availability.KNOWN,
            pipeline.getOverlayValue(position).getAvailability());
        assertEquals(5L, pipeline.getOverlayValue(position).getLastSequence());

        logger.close();
        List<String> lines = Files.readAllLines(logger.getLogFile(), StandardCharsets.UTF_8);
        assertEquals(11, lines.size());
        assertTrue(lines.get(0).contains("\"component\":\"observation\""));
        assertTrue(lines.get(0).contains("\"event_type\":\"chunk_loaded_observed\""));
        assertTrue(lines.get(1).contains("\"component\":\"block_overlay\""));
        assertTrue(lines.get(8).contains("\"event_type\":\"block_state_observed\""));
        assertTrue(lines.get(9).contains("\"event_type\":\"overlay_refreshed\""));
        assertTrue(lines.get(10).contains("\"event_type\":\"session_end\""));
    }
}
