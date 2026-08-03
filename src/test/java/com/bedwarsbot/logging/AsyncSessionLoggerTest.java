package com.bedwarsbot.logging;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class AsyncSessionLoggerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesOrderedSchemaVersionedJsonLinesAndCloses() throws Exception {
        Path logDirectory = temporaryFolder.newFolder("logs").toPath();
        AsyncSessionLogger logger = new AsyncSessionLogger(logDirectory, 8, "test-session");

        assertTrue(logger.tryLog(
            "test",
            "test_event",
            12L,
            34L,
            Collections.singletonMap("mode", "DISABLED")
        ));
        logger.close();

        List<String> lines = Files.readAllLines(logger.getLogFile(), StandardCharsets.UTF_8);
        assertEquals(8, logger.getQueueCapacity());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"schema_version\":1"));
        assertTrue(lines.get(0).contains("\"sequence\":0"));
        assertTrue(lines.get(0).contains("\"event_type\":\"test_event\""));
        assertTrue(lines.get(1).contains("\"sequence\":1"));
        assertTrue(lines.get(1).contains("\"event_type\":\"session_end\""));
        assertEquals(0L, logger.getDroppedRecords());
        assertNull(logger.getFailureMessage());
    }
}
