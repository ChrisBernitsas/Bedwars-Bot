package com.bedwarsbot.world.canonical;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public final class CanonicalSection {
    public static final int EDGE_LENGTH = 16;
    public static final int POSITION_COUNT = 4096;
    public static final int UNKNOWN_PALETTE_INDEX = -1;

    private final int sectionIndex;
    private final CoverageStatus coverageStatus;
    private final List<CanonicalBlockState> palette;
    private final int[] paletteIndices;

    public CanonicalSection(
        int sectionIndex,
        CoverageStatus coverageStatus,
        List<CanonicalBlockState> palette,
        int[] paletteIndices
    ) {
        this.sectionIndex = sectionIndex;
        this.coverageStatus = coverageStatus;
        int[] indicesCopy = paletteIndices == null ? null : paletteIndices.clone();
        List<CanonicalBlockState> paletteCopy = palette == null
            ? null
            : new ArrayList<CanonicalBlockState>(palette);
        if (canCanonicalize(paletteCopy)) {
            List<CanonicalBlockState> sorted = new ArrayList<CanonicalBlockState>(paletteCopy);
            Collections.sort(sorted);
            if (indicesCopy != null) {
                for (int index = 0; index < indicesCopy.length; index++) {
                    int oldPaletteIndex = indicesCopy[index];
                    if (oldPaletteIndex >= 0 && oldPaletteIndex < paletteCopy.size()) {
                        indicesCopy[index] = Collections.binarySearch(
                            sorted,
                            paletteCopy.get(oldPaletteIndex)
                        );
                    }
                }
            }
            paletteCopy = sorted;
        }
        this.palette = paletteCopy == null
            ? null
            : Collections.unmodifiableList(paletteCopy);
        this.paletteIndices = indicesCopy;
    }

    public static CanonicalSection unknown(int sectionIndex) {
        int[] indices = new int[POSITION_COUNT];
        Arrays.fill(indices, UNKNOWN_PALETTE_INDEX);
        return new CanonicalSection(
            sectionIndex,
            CoverageStatus.UNKNOWN,
            Collections.<CanonicalBlockState>emptyList(),
            indices
        );
    }

    public static CanonicalSection fromKnownStates(
        int sectionIndex,
        Map<Integer, CanonicalBlockState> knownStates,
        boolean complete
    ) {
        if (knownStates == null) {
            throw new IllegalArgumentException("knownStates must not be null");
        }
        if (complete && knownStates.size() != POSITION_COUNT) {
            throw new IllegalArgumentException("complete section requires exactly 4096 states");
        }
        if (!complete && knownStates.isEmpty()) {
            return unknown(sectionIndex);
        }

        Set<CanonicalBlockState> unique = new TreeSet<CanonicalBlockState>();
        for (Map.Entry<Integer, CanonicalBlockState> entry : knownStates.entrySet()) {
            if (entry.getKey() == null
                || entry.getKey().intValue() < 0
                || entry.getKey().intValue() >= POSITION_COUNT
                || entry.getValue() == null) {
                throw new IllegalArgumentException("known section state is invalid");
            }
            unique.add(entry.getValue());
        }
        List<CanonicalBlockState> palette = new ArrayList<CanonicalBlockState>(unique);
        int[] indices = new int[POSITION_COUNT];
        Arrays.fill(indices, UNKNOWN_PALETTE_INDEX);
        for (Map.Entry<Integer, CanonicalBlockState> entry : knownStates.entrySet()) {
            indices[entry.getKey().intValue()] = Collections.binarySearch(
                palette,
                entry.getValue()
            );
        }
        return new CanonicalSection(
            sectionIndex,
            complete ? CoverageStatus.COMPLETE : CoverageStatus.PARTIAL,
            palette,
            indices
        );
    }

    public int getSectionIndex() { return sectionIndex; }
    public CoverageStatus getCoverageStatus() { return coverageStatus; }
    public List<CanonicalBlockState> getPalette() { return palette; }
    public int[] copyPaletteIndices() {
        return paletteIndices == null ? null : paletteIndices.clone();
    }

    public int getKnownPositionCount() {
        if (paletteIndices == null) return 0;
        int known = 0;
        for (int paletteIndex : paletteIndices) {
            if (paletteIndex != UNKNOWN_PALETTE_INDEX) known++;
        }
        return known;
    }

    public boolean isKnown(int positionIndex) {
        requirePositionIndex(positionIndex);
        return paletteIndices != null
            && paletteIndices[positionIndex] != UNKNOWN_PALETTE_INDEX;
    }

    public Optional<CanonicalBlockState> getState(int positionIndex) {
        requirePositionIndex(positionIndex);
        if (!isKnown(positionIndex)) {
            return Optional.empty();
        }
        int paletteIndex = paletteIndices[positionIndex];
        if (palette == null || paletteIndex < 0 || paletteIndex >= palette.size()) {
            throw new IllegalStateException("section has an invalid palette index");
        }
        return Optional.of(palette.get(paletteIndex));
    }

    public Optional<CanonicalBlockState> getState(int localX, int localY, int localZ) {
        if (localX < 0 || localX >= EDGE_LENGTH
            || localY < 0 || localY >= EDGE_LENGTH
            || localZ < 0 || localZ >= EDGE_LENGTH) {
            throw new IllegalArgumentException("local coordinates must be between 0 and 15");
        }
        return getState(localY << 8 | localZ << 4 | localX);
    }

    private static void requirePositionIndex(int positionIndex) {
        if (positionIndex < 0 || positionIndex >= POSITION_COUNT) {
            throw new IllegalArgumentException("positionIndex must be between 0 and 4095");
        }
    }

    private static boolean canCanonicalize(List<CanonicalBlockState> palette) {
        if (palette == null) return false;
        Set<CanonicalBlockState> unique = new TreeSet<CanonicalBlockState>();
        for (CanonicalBlockState state : palette) {
            if (state == null || !unique.add(state)) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CanonicalSection)) return false;
        CanonicalSection that = (CanonicalSection) other;
        return sectionIndex == that.sectionIndex
            && coverageStatus == that.coverageStatus
            && (palette == null ? that.palette == null : palette.equals(that.palette))
            && Arrays.equals(paletteIndices, that.paletteIndices);
    }

    @Override
    public int hashCode() {
        int result = sectionIndex;
        result = 31 * result + (coverageStatus == null ? 0 : coverageStatus.hashCode());
        result = 31 * result + (palette == null ? 0 : palette.hashCode());
        result = 31 * result + Arrays.hashCode(paletteIndices);
        return result;
    }
}
