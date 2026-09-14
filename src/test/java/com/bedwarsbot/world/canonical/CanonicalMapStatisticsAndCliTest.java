package com.bedwarsbot.world.canonical;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CanonicalMapStatisticsAndCliTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void statisticsCountCoverageAndKnownStates() {
        CanonicalMapStatistics statistics = CanonicalMapStatistics.calculate(
            CanonicalMapTestFixtures.validMap()
        );

        assertEquals(2, statistics.getChunkCount());
        assertEquals(2, statistics.getSectionCount());
        assertEquals(1, statistics.getCompleteSections());
        assertEquals(1, statistics.getPartialSections());
        assertEquals(2L, statistics.getUnknownOrMissingSections());
        assertEquals(4098L, statistics.getKnownPositions());
        assertEquals(4097L, statistics.getKnownAirPositions());
        assertEquals(1L, statistics.getKnownNonAirPositions());
        assertEquals(3, statistics.getPaletteEntries());
    }

    @Test
    public void statisticsCountLandmarksByType() {
        CanonicalMapStatistics statistics = CanonicalMapStatistics.calculate(
            CanonicalMapTestFixtures.validMap()
        );

        assertEquals(4, statistics.getLandmarkCount());
        assertEquals(Integer.valueOf(1),
            statistics.getLandmarksByType().get(LandmarkType.TEAM_SPAWN));
        assertEquals(Integer.valueOf(1),
            statistics.getLandmarksByType().get(LandmarkType.BED));
        assertEquals(Integer.valueOf(0),
            statistics.getLandmarksByType().get(LandmarkType.EMERALD_GENERATOR));
    }

    @Test
    public void statisticsExposeStableExpectedHash() {
        assertEquals(
            "f2fd6be7f3acfda9ce115f820e3dfd7178b890aef00b52310aab4131acb0d9c8",
            CanonicalMapStatistics.calculate(
                CanonicalMapTestFixtures.validMap()
            ).getGeometrySha256()
        );
    }

    @Test
    public void validatorCommandSucceedsForSyntheticFixture() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exitCode = CanonicalMapValidatorMain.run(
            new String[] {CanonicalMapTestFixtures.fixturePath().toString()},
            new PrintStream(output, true, "UTF-8"),
            new PrintStream(new ByteArrayOutputStream(), true, "UTF-8")
        );
        String report = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertEquals(0, exitCode);
        assertTrue(report.contains("Map ID: synthetic-test"));
        assertTrue(report.contains("Known air: 4097"));
        assertTrue(report.contains("Result: VALID"));
    }

    @Test
    public void validatorCommandReturnsNonzeroForInvalidMap() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exitCode = CanonicalMapValidatorMain.run(
            new String[] {temporaryFolder.newFolder("invalid").getAbsolutePath()},
            new PrintStream(output, true, "UTF-8"),
            new PrintStream(new ByteArrayOutputStream(), true, "UTF-8")
        );
        String report = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertEquals(1, exitCode);
        assertTrue(report.contains("Validation errors: 1"));
        assertTrue(report.contains("Result: INVALID"));
    }

    @Test
    public void validatorCommandReturnsUsageExitCodeForWrongArguments() throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exitCode = CanonicalMapValidatorMain.run(
            new String[0],
            new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"),
            new PrintStream(errors, true, "UTF-8")
        );

        assertEquals(2, exitCode);
        assertTrue(new String(errors.toByteArray(), StandardCharsets.UTF_8)
            .contains("Usage: validate-canonical-map"));
    }
}
