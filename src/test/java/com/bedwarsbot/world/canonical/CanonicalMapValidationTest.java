package com.bedwarsbot.world.canonical;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CanonicalMapValidationTest {
    @Test
    public void validSyntheticMapHasNoErrors() {
        assertTrue(validate(CanonicalMapTestFixtures.validMap()).isValid());
    }

    @Test
    public void invalidBoundsAreReported() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        CanonicalMap invalid = CanonicalMapTestFixtures.withManifest(
            source,
            copyManifest(source.getManifest(), 1, 0,
                new MapBounds(10, 20, 10, 0, 10, 0), null)
        );

        assertHasCode(invalid, "INVALID_BOUNDS");
    }

    @Test
    public void invalidChunkCoordinatesAreReported() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        CanonicalChunk invalidChunk = new CanonicalChunk(
            99, 99, Arrays.asList(CanonicalSection.unknown(0))
        );

        assertHasCode(CanonicalMapTestFixtures.withChunks(
            source, Arrays.asList(invalidChunk)), "CHUNK_OUT_OF_BOUNDS");
    }

    @Test
    public void invalidSectionIndexIsReported() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        CanonicalChunk invalidChunk = new CanonicalChunk(
            0, 0, Arrays.asList(CanonicalSection.unknown(16))
        );

        assertHasCode(CanonicalMapTestFixtures.withChunks(
            source, Arrays.asList(invalidChunk)), "INVALID_SECTION_INDEX");
    }

    @Test
    public void invalidPaletteIndexIsReported() {
        int[] indices = unknownIndices();
        indices[0] = 1;
        CanonicalSection invalid = new CanonicalSection(
            0, CoverageStatus.PARTIAL,
            Arrays.asList(CanonicalBlockState.AIR), indices
        );

        assertHasCode(mapWithSingleSection(invalid), "INVALID_PALETTE_INDEX");
    }

    @Test
    public void invalidBlockMetadataIsReported() {
        int[] indices = unknownIndices();
        indices[0] = 0;
        CanonicalSection invalid = new CanonicalSection(
            0, CoverageStatus.PARTIAL,
            Arrays.asList(new CanonicalBlockState(1, 16)), indices
        );

        assertHasCode(mapWithSingleSection(invalid), "INVALID_BLOCK_STATE");
    }

    @Test
    public void completeSectionCannotHaveUnknownPositions() {
        int[] indices = unknownIndices();
        indices[0] = 0;
        CanonicalSection invalid = new CanonicalSection(
            0, CoverageStatus.COMPLETE,
            Arrays.asList(CanonicalBlockState.AIR), indices
        );

        assertHasCode(mapWithSingleSection(invalid), "INCOMPLETE_COMPLETE_SECTION");
    }

    @Test
    public void partialSectionCannotClaimFullCoverage() {
        CanonicalSection complete = CanonicalMapTestFixtures.validMap()
            .findChunk(0, 0).get().findSection(0).get();
        CanonicalSection invalid = new CanonicalSection(
            0, CoverageStatus.PARTIAL,
            complete.getPalette(), complete.copyPaletteIndices()
        );

        assertHasCode(mapWithSingleSection(invalid), "INVALID_PARTIAL_COVERAGE");
    }

    @Test
    public void duplicateLandmarkIdIsReported() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        List<CanonicalLandmark> landmarks = CanonicalMapTestFixtures.mutableLandmarks(source);
        landmarks.add(landmarks.get(0));

        assertHasCode(CanonicalMapTestFixtures.withLandmarks(source, landmarks),
            "DUPLICATE_LANDMARK_ID");
    }

    @Test
    public void conflictingLandmarkIsReported() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        List<CanonicalLandmark> landmarks = CanonicalMapTestFixtures.mutableLandmarks(source);
        landmarks.add(CanonicalMapTestFixtures.landmark(
            landmarks.get(0).getLandmarkId(), LandmarkType.FORGE, 10, 1, 10
        ));

        assertHasCode(CanonicalMapTestFixtures.withLandmarks(source, landmarks),
            "CONFLICTING_LANDMARK");
    }

    @Test
    public void outOfBoundsLandmarkIsReported() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        List<CanonicalLandmark> landmarks = new ArrayList<CanonicalLandmark>();
        landmarks.add(CanonicalMapTestFixtures.landmark(
            "outside", LandmarkType.NAVIGATION_LANDMARK, 200, 1, 200
        ));

        assertHasCode(CanonicalMapTestFixtures.withLandmarks(source, landmarks),
            "LANDMARK_OUT_OF_BOUNDS");
    }

    @Test
    public void unsupportedSchemaIsReportedStructurally() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        CanonicalMap invalid = CanonicalMapTestFixtures.withManifest(
            source,
            copyManifest(source.getManifest(), 2, 0,
                source.getManifest().getBounds(), null)
        );

        assertHasCode(invalid, "UNSUPPORTED_SCHEMA");
    }

    @Test
    public void invalidDimensionIsReported() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        CanonicalMap invalid = CanonicalMapTestFixtures.withManifest(
            source,
            copyManifest(source.getManifest(), 1, 7,
                source.getManifest().getBounds(), null)
        );

        assertHasCode(invalid, "INVALID_DIMENSION");
    }

    @Test
    public void invalidSourceChecksumIsReported() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        CanonicalMap invalid = CanonicalMapTestFixtures.withManifest(
            source,
            copyManifest(source.getManifest(), 1, 0,
                source.getManifest().getBounds(), "not-a-sha256")
        );

        assertHasCode(invalid, "INVALID_SOURCE_CHECKSUM");
    }

    @Test
    public void impossibleAcquisitionDateIsReported() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        CanonicalMapManifest original = source.getManifest();
        CanonicalMapManifest manifest = new CanonicalMapManifest(
            original.getSchemaVersion(), original.getMapId(), original.getDisplayName(),
            original.getMapRevision(), original.getBedwarsMode(),
            original.getMinecraftVersion(), original.getDimension(), original.getBounds(),
            original.getSourceProvenance(), "2026-02-30", original.getSourceReference(),
            original.getSourceChecksum(), original.getValidationStatus(),
            original.getNotes(), original.getGeometryFormat(),
            original.getGeometryFormatVersion(), original.getLandmarkFormat(),
            original.getLandmarkFormatVersion()
        );

        assertHasCode(CanonicalMapTestFixtures.withManifest(source, manifest),
            "INVALID_ACQUISITION_DATE");
    }

    @Test
    public void unknownCoverageCannotCarryPaletteData() {
        CanonicalSection invalid = new CanonicalSection(
            0,
            CoverageStatus.UNKNOWN,
            Arrays.asList(CanonicalBlockState.AIR),
            unknownIndices()
        );

        assertHasCode(mapWithSingleSection(invalid), "UNKNOWN_COVERAGE_HAS_PALETTE");
    }

    @Test
    public void knownPositionsRequireNonemptyPalette() {
        int[] indices = unknownIndices();
        indices[0] = 0;
        CanonicalSection invalid = new CanonicalSection(
            0,
            CoverageStatus.PARTIAL,
            Arrays.<CanonicalBlockState>asList(),
            indices
        );

        assertHasCode(mapWithSingleSection(invalid), "EMPTY_PALETTE");
    }

    @Test
    public void nullEntriesReachStructuredValidation() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        CanonicalMap nullChunk = new CanonicalMap(
            source.getManifest(), Arrays.asList((CanonicalChunk) null), source.getLandmarks()
        );
        CanonicalMap nullSection = CanonicalMapTestFixtures.withChunks(
            source,
            Arrays.asList(new CanonicalChunk(
                0, 0, Arrays.asList((CanonicalSection) null)
            ))
        );
        CanonicalMap nullLandmark = CanonicalMapTestFixtures.withLandmarks(
            source, Arrays.asList((CanonicalLandmark) null)
        );

        assertHasCode(nullChunk, "CHUNK_REQUIRED");
        assertHasCode(nullSection, "SECTION_REQUIRED");
        assertHasCode(nullLandmark, "LANDMARK_REQUIRED");
    }

    @Test
    public void duplicateChunkCoordinatesAreReported() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();

        assertHasCode(CanonicalMapTestFixtures.withChunks(
            source,
            Arrays.asList(source.getChunks().get(0), source.getChunks().get(0))),
            "DUPLICATE_CHUNK");
    }

    @Test
    public void excessiveChunkCountIsReported() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        List<CanonicalChunk> chunks = new ArrayList<CanonicalChunk>();
        for (int index = 0; index <= CanonicalMapLimits.MAX_GEOMETRY_FILES; index++) {
            chunks.add(source.getChunks().get(0));
        }

        assertHasCode(CanonicalMapTestFixtures.withChunks(source, chunks),
            "TOO_MANY_CHUNKS");
    }

    @Test
    public void excessiveSectionCountIsReported() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        List<CanonicalSection> sections = new ArrayList<CanonicalSection>();
        for (int index = 0;
            index <= CanonicalMapLimits.MAX_SECTIONS_PER_CHUNK;
            index++) {
            sections.add(CanonicalSection.unknown(index % 16));
        }

        assertHasCode(CanonicalMapTestFixtures.withChunks(source, Arrays.asList(
            new CanonicalChunk(0, 0, sections)
        )), "TOO_MANY_SECTIONS");
    }

    @Test
    public void excessivePaletteCountIsReported() {
        List<CanonicalBlockState> palette = new ArrayList<CanonicalBlockState>();
        for (int index = 0;
            index <= CanonicalMapLimits.MAX_PALETTE_ENTRIES;
            index++) {
            palette.add(CanonicalBlockState.AIR);
        }
        int[] indices = unknownIndices();
        indices[0] = 0;

        assertHasCode(mapWithSingleSection(new CanonicalSection(
            0, CoverageStatus.PARTIAL, palette, indices
        )), "TOO_MANY_PALETTE_ENTRIES");
    }

    @Test
    public void excessiveLandmarkCountIsReported() {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        List<CanonicalLandmark> landmarks = new ArrayList<CanonicalLandmark>();
        CanonicalLandmark landmark = source.getLandmarks().get(0);
        for (int index = 0; index <= CanonicalMapLimits.MAX_LANDMARKS; index++) {
            landmarks.add(landmark);
        }

        assertHasCode(CanonicalMapTestFixtures.withLandmarks(source, landmarks),
            "TOO_MANY_LANDMARKS");
    }

    private static CanonicalMap mapWithSingleSection(CanonicalSection section) {
        CanonicalMap source = CanonicalMapTestFixtures.validMap();
        return CanonicalMapTestFixtures.withChunks(
            source,
            Arrays.asList(new CanonicalChunk(0, 0, Arrays.asList(section)))
        );
    }

    private static int[] unknownIndices() {
        int[] indices = new int[4096];
        Arrays.fill(indices, -1);
        return indices;
    }

    private static ValidationReport validate(CanonicalMap map) {
        return new CanonicalMapValidator().validate(map);
    }

    private static void assertHasCode(CanonicalMap map, String code) {
        ValidationReport report = validate(map);
        assertFalse(report.isValid());
        assertTrue(report.getErrors().toString(), report.getErrors().stream()
            .anyMatch(issue -> code.equals(issue.getCode())));
    }

    private static CanonicalMapManifest copyManifest(
        CanonicalMapManifest source,
        int schema,
        int dimension,
        MapBounds bounds,
        String checksum
    ) {
        return new CanonicalMapManifest(
            schema,
            source.getMapId(),
            source.getDisplayName(),
            source.getMapRevision(),
            source.getBedwarsMode(),
            source.getMinecraftVersion(),
            dimension,
            bounds,
            source.getSourceProvenance(),
            source.getAcquisitionDate(),
            source.getSourceReference(),
            checksum,
            source.getValidationStatus(),
            source.getNotes(),
            source.getGeometryFormat(),
            source.getGeometryFormatVersion(),
            source.getLandmarkFormat(),
            source.getLandmarkFormatVersion()
        );
    }
}
