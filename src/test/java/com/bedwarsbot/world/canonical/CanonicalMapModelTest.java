package com.bedwarsbot.world.canonical;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CanonicalMapModelTest {
    @Test
    public void knownAirAndUnknownAreDistinct() {
        Map<Integer, CanonicalBlockState> known =
            new HashMap<Integer, CanonicalBlockState>();
        known.put(Integer.valueOf(0), CanonicalBlockState.AIR);
        CanonicalSection section = CanonicalSection.fromKnownStates(0, known, false);

        assertTrue(section.isKnown(0));
        assertTrue(section.getState(0).get().isAir());
        assertFalse(section.isKnown(1));
        assertFalse(section.getState(1).isPresent());
    }

    @Test
    public void completeSectionHasFullCoverage() {
        CanonicalSection section = CanonicalMapTestFixtures.validMap()
            .findChunk(0, 0).get().findSection(0).get();

        assertEquals(CoverageStatus.COMPLETE, section.getCoverageStatus());
        assertEquals(4096, section.getKnownPositionCount());
    }

    @Test
    public void partialSectionRetainsUnknownPositions() {
        CanonicalSection section = CanonicalMapTestFixtures.validMap()
            .findChunk(1, 0).get().findSection(0).get();

        assertEquals(CoverageStatus.PARTIAL, section.getCoverageStatus());
        assertEquals(2, section.getKnownPositionCount());
        assertFalse(section.getState(2).isPresent());
    }

    @Test
    public void missingSectionIsUnknown() {
        CanonicalChunk chunk = CanonicalMapTestFixtures.validMap().findChunk(0, 0).get();

        assertFalse(chunk.findSection(1).isPresent());
    }

    @Test
    public void paletteEncodingIsSortedAndDeduplicated() {
        Map<Integer, CanonicalBlockState> known =
            new HashMap<Integer, CanonicalBlockState>();
        known.put(Integer.valueOf(3), new CanonicalBlockState(35, 14));
        known.put(Integer.valueOf(1), CanonicalBlockState.AIR);
        known.put(Integer.valueOf(2), new CanonicalBlockState(35, 14));
        CanonicalSection section = CanonicalSection.fromKnownStates(0, known, false);

        assertEquals(Arrays.asList(
            CanonicalBlockState.AIR,
            new CanonicalBlockState(35, 14)
        ), section.getPalette());
    }

    @Test
    public void paletteDecodingReturnsOriginalStates() {
        CanonicalSection section = CanonicalMapTestFixtures.validMap()
            .findChunk(1, 0).get().findSection(0).get();

        assertEquals(CanonicalBlockState.AIR, section.getState(0).get());
        assertEquals(new CanonicalBlockState(35, 14), section.getState(1).get());
    }

    @Test
    public void modelDefensivelyCopiesMutableArrays() {
        int[] indices = new int[4096];
        Arrays.fill(indices, -1);
        indices[0] = 0;
        CanonicalSection section = new CanonicalSection(
            0,
            CoverageStatus.PARTIAL,
            Arrays.asList(CanonicalBlockState.AIR),
            indices
        );
        indices[0] = -1;
        int[] returned = section.copyPaletteIndices();
        returned[0] = -1;

        assertTrue(section.isKnown(0));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void mapExposesUnmodifiableCollections() {
        CanonicalMapTestFixtures.validMap().getChunks().clear();
    }

    @Test
    public void chunkAndLandmarkOrderingIsDeterministic() {
        CanonicalMap original = CanonicalMapTestFixtures.validMap();
        CanonicalMap reversed = new CanonicalMap(
            original.getManifest(),
            Arrays.asList(original.getChunks().get(1), original.getChunks().get(0)),
            Arrays.asList(
                original.getLandmarks().get(3),
                original.getLandmarks().get(2),
                original.getLandmarks().get(1),
                original.getLandmarks().get(0)
            )
        );

        assertEquals(original, reversed);
    }
}
