package com.bedwarsbot.world.canonical;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.Assume;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CanonicalMapSerializationTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void manifestRoundTrips() throws Exception {
        CanonicalMap expected = CanonicalMapTestFixtures.validMap();
        CanonicalMap actual = saveAndLoad(expected);

        assertEquals(expected.getManifest(), actual.getManifest());
    }

    @Test
    public void geometryRoundTrips() throws Exception {
        CanonicalMap expected = CanonicalMapTestFixtures.validMap();
        CanonicalMap actual = saveAndLoad(expected);

        assertEquals(expected.getChunks(), actual.getChunks());
    }

    @Test
    public void landmarksRoundTrip() throws Exception {
        CanonicalMap expected = CanonicalMapTestFixtures.validMap();
        CanonicalMap actual = saveAndLoad(expected);

        assertEquals(expected.getLandmarks(), actual.getLandmarks());
    }

    @Test
    public void entireCanonicalMapRoundTrips() throws Exception {
        CanonicalMap expected = CanonicalMapTestFixtures.validMap();

        assertEquals(expected, saveAndLoad(expected));
    }

    @Test
    public void deterministicSerializationIgnoresInsertionOrder() throws Exception {
        CanonicalMap original = CanonicalMapTestFixtures.validMap();
        CanonicalMap reordered = new CanonicalMap(
            original.getManifest(),
            Arrays.asList(original.getChunks().get(1), original.getChunks().get(0)),
            Arrays.asList(
                original.getLandmarks().get(3),
                original.getLandmarks().get(1),
                original.getLandmarks().get(0),
                original.getLandmarks().get(2)
            )
        );
        Path first = temporaryFolder.newFolder("first").toPath();
        Path second = temporaryFolder.newFolder("second").toPath();
        CanonicalMapSerializer serializer = new CanonicalMapSerializer();
        serializer.save(original, first);
        serializer.save(reordered, second);

        assertEquals(readTree(first), readTree(second));
    }

    @Test
    public void stableOutputAcrossRepeatedSaves() throws Exception {
        Path directory = temporaryFolder.newFolder("repeated").toPath();
        CanonicalMapSerializer serializer = new CanonicalMapSerializer();
        serializer.save(CanonicalMapTestFixtures.validMap(), directory);
        Map<String, String> first = readTree(directory);
        serializer.save(CanonicalMapTestFixtures.validMap(), directory);

        assertEquals(first, readTree(directory));
    }

    @Test
    public void deterministicHashIgnoresPaletteAndMapInsertionOrder() {
        CanonicalMap original = CanonicalMapTestFixtures.validMap();
        CanonicalSection section = original.findChunk(1, 0).get().findSection(0).get();
        int[] reversedIndices = section.copyPaletteIndices();
        reversedIndices[0] = 1;
        reversedIndices[1] = 0;
        CanonicalSection reversedPalette = new CanonicalSection(
            0,
            CoverageStatus.PARTIAL,
            Arrays.asList(new CanonicalBlockState(35, 14), CanonicalBlockState.AIR),
            reversedIndices
        );
        CanonicalChunk changedSecond = new CanonicalChunk(
            1, 0, Arrays.asList(reversedPalette)
        );
        CanonicalMap equivalent = new CanonicalMap(
            original.getManifest(),
            Arrays.asList(changedSecond, original.getChunks().get(0)),
            original.getLandmarks()
        );

        assertEquals(
            new CanonicalGeometryHasher().sha256(original),
            new CanonicalGeometryHasher().sha256(equivalent)
        );
    }

    @Test
    public void deterministicSerializationCanonicalizesPaletteOrder() throws Exception {
        CanonicalMap original = CanonicalMapTestFixtures.validMap();
        CanonicalSection section = original.findChunk(1, 0).get().findSection(0).get();
        int[] reversedIndices = section.copyPaletteIndices();
        reversedIndices[0] = 1;
        reversedIndices[1] = 0;
        CanonicalMap equivalent = new CanonicalMap(
            original.getManifest(),
            Arrays.asList(
                original.getChunks().get(0),
                new CanonicalChunk(1, 0, Collections.singletonList(
                    new CanonicalSection(
                        0,
                        CoverageStatus.PARTIAL,
                        Arrays.asList(
                            new CanonicalBlockState(35, 14),
                            CanonicalBlockState.AIR
                        ),
                        reversedIndices
                    )
                ))
            ),
            original.getLandmarks()
        );
        Path first = temporaryFolder.newFolder("palette-first").toPath();
        Path second = temporaryFolder.newFolder("palette-second").toPath();
        CanonicalMapSerializer serializer = new CanonicalMapSerializer();

        serializer.save(original, first);
        serializer.save(equivalent, second);

        assertEquals(readTree(first), readTree(second));
    }

    @Test
    public void geometryHashExcludesManifestMetadataAndLandmarks() {
        CanonicalMap original = CanonicalMapTestFixtures.validMap();
        CanonicalMapManifest manifest = new CanonicalMapManifest(
            1, "other-id", "Other display", "2", "another-mode", "1.8.9", 0,
            original.getManifest().getBounds(), "different provenance", "2026-09-14",
            "different reference", null, ValidationStatus.VALIDATED, "different notes",
            "palette-section-json", 1, "landmarks-json", 1
        );
        CanonicalMap metadataChanged = new CanonicalMap(
            manifest,
            original.getChunks(),
            Collections.singletonList(CanonicalMapTestFixtures.landmark(
                "navigation", LandmarkType.NAVIGATION_LANDMARK, 8, 1, 8
            ))
        );

        assertEquals(
            new CanonicalGeometryHasher().sha256(original),
            new CanonicalGeometryHasher().sha256(metadataChanged)
        );
    }

    @Test
    public void knownAirAndUnknownProduceDifferentGeometryHashes() {
        CanonicalMap original = CanonicalMapTestFixtures.validMap();
        int[] indices = new int[CanonicalSection.POSITION_COUNT];
        Arrays.fill(indices, CanonicalSection.UNKNOWN_PALETTE_INDEX);
        indices[1] = 0;
        CanonicalSection withoutKnownAir = new CanonicalSection(
            0,
            CoverageStatus.PARTIAL,
            Collections.singletonList(new CanonicalBlockState(35, 14)),
            indices
        );
        CanonicalMap changedCoverage = new CanonicalMap(
            original.getManifest(),
            Arrays.asList(
                original.getChunks().get(0),
                new CanonicalChunk(1, 0, Collections.singletonList(withoutKnownAir))
            ),
            original.getLandmarks()
        );

        assertTrue(!new CanonicalGeometryHasher().sha256(original).equals(
            new CanonicalGeometryHasher().sha256(changedCoverage)
        ));
    }

    @Test
    public void suppliedSourceFieldsRoundTripIndependentlyOfGeometryHash()
        throws Exception {
        CanonicalMap original = CanonicalMapTestFixtures.validMap();
        CanonicalMapManifest source = original.getManifest();
        CanonicalMapManifest manifest = new CanonicalMapManifest(
            source.getSchemaVersion(), source.getMapId(), source.getDisplayName(),
            source.getMapRevision(), source.getBedwarsMode(), source.getMinecraftVersion(),
            source.getDimension(), source.getBounds(), source.getSourceProvenance(),
            "2026-09-14", "local:test-source",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            source.getValidationStatus(), source.getNotes(), source.getGeometryFormat(),
            source.getGeometryFormatVersion(), source.getLandmarkFormat(),
            source.getLandmarkFormatVersion()
        );
        CanonicalMap withSource = CanonicalMapTestFixtures.withManifest(original, manifest);

        CanonicalMap loaded = saveAndLoad(withSource);

        assertEquals(manifest, loaded.getManifest());
        assertEquals(new CanonicalGeometryHasher().sha256(original),
            new CanonicalGeometryHasher().sha256(loaded));
    }

    @Test
    public void manifestFileOrderingDoesNotAffectLoadedGeometry() throws Exception {
        Path directory = savedDirectory("manifest-order");
        replace(directory.resolve("manifest.json"),
            "\"geometry/chunk_0_0.json\",\n    \"geometry/chunk_1_0.json\"",
            "\"geometry/chunk_1_0.json\",\n    \"geometry/chunk_0_0.json\"");

        assertEquals(CanonicalMapTestFixtures.validMap(),
            new CanonicalMapSerializer().load(directory));
    }

    @Test
    public void unsupportedSchemaIsRejected() throws Exception {
        Path directory = savedDirectory("unsupported");
        replace(directory.resolve("manifest.json"),
            "\"schema_version\": 1", "\"schema_version\": 2");

        assertLoadError(directory, "UNSUPPORTED_SCHEMA");
    }

    @Test
    public void malformedInputIsRejected() throws Exception {
        Path directory = savedDirectory("malformed");
        Files.write(directory.resolve("manifest.json"),
            "{not json}".getBytes(StandardCharsets.UTF_8));

        assertLoadError(directory, "MALFORMED_JSON");
    }

    @Test
    public void truncatedSectionDataIsRejected() throws Exception {
        Path directory = savedDirectory("truncated");
        Path chunk = directory.resolve("geometry/chunk_1_0.json");
        replace(chunk, "\"state_data\": \"AAE=\"", "\"state_data\": \"AA==\"");

        assertLoadError(directory, "TRUNCATED_STATE_DATA");
    }

    @Test
    public void invalidGeometryHashIsRejected() throws Exception {
        Path directory = savedDirectory("bad-hash");
        replace(directory.resolve("manifest.json"),
            new CanonicalGeometryHasher().sha256(CanonicalMapTestFixtures.validMap()),
            "0000000000000000000000000000000000000000000000000000000000000000");

        assertLoadError(directory, "GEOMETRY_HASH_MISMATCH");
    }

    @Test
    public void pathTraversalIsRejected() throws Exception {
        Path directory = savedDirectory("traversal");
        replace(directory.resolve("manifest.json"),
            "geometry/chunk_0_0.json", "../outside.json");

        assertLoadError(directory, "UNSAFE_PATH");
    }

    @Test
    public void nonNormalizedContainedPathIsRejected() throws Exception {
        Path directory = savedDirectory("contained-traversal");
        replace(directory.resolve("manifest.json"),
            "geometry/chunk_0_0.json", "geometry/../landmarks.json");

        assertLoadError(directory, "UNSAFE_PATH");
    }

    @Test
    public void symlinkedPathComponentIsRejected() throws Exception {
        Path directory = savedDirectory("symlink-component");
        Path geometry = directory.resolve("geometry");
        Path actualGeometry = directory.resolve("actual-geometry");
        Files.move(geometry, actualGeometry);
        try {
            Files.createSymbolicLink(geometry, actualGeometry.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException
            | SecurityException unsupported) {
            Assume.assumeNoException(unsupported);
        }

        assertLoadError(directory, "UNSAFE_PATH");
    }

    @Test
    public void oversizedJsonFileIsRejectedBeforeParsing() throws Exception {
        Path directory = savedDirectory("oversized-json");
        byte[] oversized = new byte[(int) CanonicalMapLimits.MAX_JSON_FILE_BYTES + 1];
        Files.write(directory.resolve("manifest.json"), oversized);

        assertLoadError(directory, "FILE_TOO_LARGE");
    }

    @Test
    public void aggregateMapByteBudgetIsEnforced() throws Exception {
        CanonicalMapSerializer.LoadBudget budget =
            new CanonicalMapSerializer.LoadBudget();
        budget.accountBytes(CanonicalMapLimits.MAX_TOTAL_MAP_BYTES, "first");
        try {
            budget.accountBytes(1L, "second");
            fail("expected aggregate byte limit rejection");
        } catch (CanonicalMapFormatException expected) {
            assertEquals("MAP_TOO_LARGE",
                expected.getValidationReport().getErrors().get(0).getCode());
        }
    }

    @Test
    public void excessiveJsonNestingIsRejectedWithoutStackOverflow() throws Exception {
        Path directory = savedDirectory("nested-json");
        String json = "{}";
        for (int depth = 0;
            depth <= CanonicalMapLimits.MAX_JSON_NESTING_DEPTH;
            depth++) {
            json = "{\"nested\":" + json + '}';
        }
        Files.write(directory.resolve("manifest.json"),
            json.getBytes(StandardCharsets.UTF_8));

        assertLoadError(directory, "JSON_LIMIT_EXCEEDED");
    }

    @Test
    public void malformedUtf8IsRejected() throws Exception {
        Path directory = savedDirectory("invalid-utf8");
        Files.write(directory.resolve("manifest.json"),
            new byte[] {'{', '"', (byte) 0xc3, '(', '"', ':', '0', '}'});

        assertLoadError(directory, "INVALID_UTF8");
    }

    @Test
    public void unpairedUnicodeSurrogateIsRejected() throws Exception {
        Path directory = savedDirectory("unpaired-surrogate");
        Files.write(directory.resolve("manifest.json"),
            "{\"value\":\"\\uD800\"}".getBytes(StandardCharsets.UTF_8));

        assertLoadError(directory, "MALFORMED_JSON");
    }

    @Test
    public void checkedInSyntheticFixtureLoads() throws Exception {
        CanonicalMap map = CanonicalMapTestFixtures.loadFixture();

        assertEquals("synthetic-test", map.getManifest().getMapId());
        assertEquals(2, map.getChunks().size());
    }

    @Test
    public void checkedInSyntheticFixtureUsesCanonicalSerialization() throws Exception {
        Path saved = temporaryFolder.newFolder("fixture-resave").toPath();
        new CanonicalMapSerializer().save(CanonicalMapTestFixtures.loadFixture(), saved);

        assertEquals(readTree(CanonicalMapTestFixtures.fixturePath()), readTree(saved));
    }

    private CanonicalMap saveAndLoad(CanonicalMap map) throws Exception {
        Path directory = temporaryFolder.newFolder().toPath();
        CanonicalMapSerializer serializer = new CanonicalMapSerializer();
        serializer.save(map, directory);
        return serializer.load(directory);
    }

    private Path savedDirectory(String name) throws Exception {
        Path directory = temporaryFolder.newFolder(name).toPath();
        new CanonicalMapSerializer().save(CanonicalMapTestFixtures.validMap(), directory);
        return directory;
    }

    private static Map<String, String> readTree(Path directory) throws Exception {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(directory)) {
            files = stream.filter(Files::isRegularFile)
                .sorted()
                .collect(Collectors.toList());
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Path file : files) {
            result.put(
                directory.relativize(file).toString(),
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            );
        }
        return result;
    }

    private static void replace(Path path, String before, String after) throws Exception {
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertTrue("fixture text to replace must exist", content.contains(before));
        Files.write(path, content.replace(before, after).getBytes(StandardCharsets.UTF_8));
    }

    private static void assertLoadError(Path directory, String expectedCode)
        throws Exception {
        try {
            new CanonicalMapSerializer().load(directory);
            fail("expected CanonicalMapFormatException");
        } catch (CanonicalMapFormatException expected) {
            assertEquals(expectedCode,
                expected.getValidationReport().getErrors().get(0).getCode());
        }
    }
}
