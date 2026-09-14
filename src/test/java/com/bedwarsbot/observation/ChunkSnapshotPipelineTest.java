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

public final class ChunkSnapshotPipelineTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void cleanShutdownDrainsQueueAndLogsLifecycleInOrder() throws Exception {
        Path logDirectory = temporaryFolder.newFolder("logs").toPath();
        AsyncSessionLogger logger = new AsyncSessionLogger(
            logDirectory,
            64,
            "chunk-snapshot-test"
        );
        ChunkSnapshotPipeline pipeline = new ChunkSnapshotPipeline(logger, 32);
        ChunkSnapshotKey key = new ChunkSnapshotKey(0, 0, 0);

        assertTrue(pipeline.tryCapture(ChunkSnapshotEvent.scheduled(
            1L, 2L, 3L, 4L, key, 1L, "SCHEDULED"
        )));
        assertTrue(pipeline.tryCapture(ChunkSnapshotEvent.sectionCaptured(
            2L,
            2L,
            3L,
            4L,
            key,
            1L,
            ChunkSectionSnapshot.uniform(0, 0),
            1
        )));
        assertTrue(pipeline.tryCapture(ChunkSnapshotEvent.aborted(
            3L, 2L, 3L, 4L, key, 1L, 1, "test_shutdown"
        )));

        pipeline.close();
        ChunkSnapshotPipeline.PipelineSnapshot snapshot =
            pipeline.getPipelineSnapshotReference().get();
        assertEquals(3L, snapshot.getProcessedEvents());
        assertEquals(0L, snapshot.getDroppedEvents());
        assertEquals(0, snapshot.getQueueDepth());
        assertEquals(1L, snapshot.getStoreSnapshot().getAbortedSnapshots());
        assertEquals(1, snapshot.getStoreSnapshot().getPartialChunks());

        ChunkSnapshotHudSnapshot hud = new ChunkSnapshotHudSnapshot(
            0,
            0,
            512,
            1L,
            0L,
            0L,
            1L,
            4096L,
            4096,
            10L,
            10L,
            10L,
            IncrementalScanBudget.StopReason.BLOCK_BUDGET,
            1L,
            0L,
            0L,
            snapshot
        );
        pipeline.logSummary(hud);

        logger.close();
        List<String> lines = Files.readAllLines(logger.getLogFile(), StandardCharsets.UTF_8);
        assertEquals(5, lines.size());
        assertTrue(lines.get(0).contains("\"event_type\":\"chunk_snapshot_scheduled\""));
        assertTrue(lines.get(1).contains(
            "\"event_type\":\"chunk_snapshot_section_captured\""
        ));
        assertTrue(lines.get(2).contains("\"event_type\":\"chunk_snapshot_aborted\""));
        assertTrue(lines.get(3).contains(
            "\"event_type\":\"chunk_snapshot_pipeline_summary\""
        ));
        assertTrue(lines.get(3).contains("\"blocks_copied_this_tick\":\"4096\""));
        assertTrue(lines.get(3).contains("\"scan_stop_reason\":\"BLOCK_BUDGET\""));
        assertTrue(lines.get(3).contains("\"partial_section_resume_count\":\"0\""));
        assertTrue(lines.get(4).contains("\"event_type\":\"session_end\""));
    }
}
