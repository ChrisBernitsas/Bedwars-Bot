package com.bedwarsbot.world.canonical;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class CanonicalMapTestFixtures {
    private CanonicalMapTestFixtures() {
    }

    static Path fixturePath() {
        return Paths.get("maps/testing/synthetic-test/1").toAbsolutePath().normalize();
    }

    static CanonicalMap loadFixture() throws Exception {
        return new CanonicalMapSerializer().load(fixturePath());
    }

    static CanonicalMap validMap() {
        Map<Integer, CanonicalBlockState> completeStates =
            new HashMap<Integer, CanonicalBlockState>();
        for (int index = 0; index < CanonicalSection.POSITION_COUNT; index++) {
            completeStates.put(Integer.valueOf(index), CanonicalBlockState.AIR);
        }
        Map<Integer, CanonicalBlockState> partialStates =
            new HashMap<Integer, CanonicalBlockState>();
        partialStates.put(Integer.valueOf(0), CanonicalBlockState.AIR);
        partialStates.put(Integer.valueOf(1), new CanonicalBlockState(35, 14));
        CanonicalChunk first = new CanonicalChunk(0, 0, Arrays.asList(
            CanonicalSection.fromKnownStates(0, completeStates, true)
        ));
        CanonicalChunk second = new CanonicalChunk(1, 0, Arrays.asList(
            CanonicalSection.fromKnownStates(0, partialStates, false)
        ));
        return new CanonicalMap(
            manifest(1, new MapBounds(0, 0, 0, 31, 31, 15)),
            Arrays.asList(first, second),
            landmarks()
        );
    }

    static CanonicalMapManifest manifest(int schemaVersion, MapBounds bounds) {
        return new CanonicalMapManifest(
            schemaVersion,
            "synthetic-test",
            "Synthetic Test Map",
            "1",
            "testing-only",
            "1.8.9",
            0,
            bounds,
            "synthetic unit-test fixture; not copied from any server map",
            null,
            null,
            null,
            ValidationStatus.UNVERIFIED,
            "Testing only",
            "palette-section-json",
            1,
            "landmarks-json",
            1
        );
    }

    static List<CanonicalLandmark> landmarks() {
        return Arrays.asList(
            landmark("spawn-alpha", LandmarkType.TEAM_SPAWN, 2, 1, 2),
            landmark("bed-alpha", LandmarkType.BED, 4, 1, 4),
            landmark("shop-alpha", LandmarkType.ITEM_SHOP, 3, 1, 6),
            landmark("diamond-center", LandmarkType.DIAMOND_GENERATOR, 15, 1, 8)
        );
    }

    static CanonicalLandmark landmark(
        String id,
        LandmarkType type,
        int x,
        int y,
        int z
    ) {
        return new CanonicalLandmark(
            id,
            type,
            new CanonicalPosition(x, y, z),
            type == LandmarkType.DIAMOND_GENERATOR ? null : Facing.EAST,
            type == LandmarkType.DIAMOND_GENERATOR ? null : "alpha",
            "Synthetic " + id,
            0.75,
            "synthetic test fixture",
            ValidationStatus.UNVERIFIED,
            null
        );
    }

    static CanonicalMap withManifest(CanonicalMap source, CanonicalMapManifest manifest) {
        return new CanonicalMap(manifest, source.getChunks(), source.getLandmarks());
    }

    static CanonicalMap withChunks(CanonicalMap source, List<CanonicalChunk> chunks) {
        return new CanonicalMap(source.getManifest(), chunks, source.getLandmarks());
    }

    static CanonicalMap withLandmarks(
        CanonicalMap source,
        List<CanonicalLandmark> landmarks
    ) {
        return new CanonicalMap(source.getManifest(), source.getChunks(), landmarks);
    }

    static List<CanonicalLandmark> mutableLandmarks(CanonicalMap map) {
        return new ArrayList<CanonicalLandmark>(map.getLandmarks());
    }
}
