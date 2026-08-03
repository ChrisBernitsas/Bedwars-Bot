package com.bedwarsbot.logging;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class AsyncSessionLoggerTest {
    private static final Pattern SEQUENCE_PATTERN = Pattern.compile("\\\"sequence\\\":([0-9]+)");

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
        assertTrue(lines.get(1).contains("\"failure\":\"\""));
        assertEquals(0L, logger.getDroppedRecords());
        assertNull(logger.getFailureMessage());
    }

    @Test
    public void concurrentProducersRemainGloballySequenceOrdered() throws Exception {
        Path logDirectory = temporaryFolder.newFolder("concurrent-logs").toPath();
        final AsyncSessionLogger logger = new AsyncSessionLogger(
            logDirectory,
            1024,
            "concurrent-session"
        );
        int producerCount = 4;
        final int recordsPerProducer = 50;
        final CountDownLatch start = new CountDownLatch(1);
        Thread[] producers = new Thread[producerCount];
        for (int producer = 0; producer < producerCount; producer++) {
            producers[producer] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int index = 0; index < recordsPerProducer; index++) {
                            logger.tryLog("test", "concurrent", index, null);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "test-log-producer-" + producer);
            producers[producer].start();
        }
        start.countDown();
        for (Thread producer : producers) {
            producer.join();
        }
        logger.close();

        List<String> lines = Files.readAllLines(logger.getLogFile(), StandardCharsets.UTF_8);
        assertEquals(producerCount * recordsPerProducer + 1, lines.size());
        for (int index = 0; index < lines.size(); index++) {
            Matcher matcher = SEQUENCE_PATTERN.matcher(lines.get(index));
            assertTrue(matcher.find());
            assertEquals(index, Integer.parseInt(matcher.group(1)));
        }
        assertEquals(0L, logger.getDroppedRecords());
    }
}
