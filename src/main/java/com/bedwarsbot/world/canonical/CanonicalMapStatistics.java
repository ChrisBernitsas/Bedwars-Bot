package com.bedwarsbot.world.canonical;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class CanonicalMapStatistics {
    private final int chunkCount;
    private final int sectionCount;
    private final int completeSections;
    private final int partialSections;
    private final long unknownOrMissingSections;
    private final long knownPositions;
    private final long knownAirPositions;
    private final long knownNonAirPositions;
    private final int paletteEntries;
    private final int minimumPaletteSize;
    private final int maximumPaletteSize;
    private final int landmarkCount;
    private final Map<LandmarkType, Integer> landmarksByType;
    private final String geometrySha256;

    private CanonicalMapStatistics(
        int chunkCount,
        int sectionCount,
        int completeSections,
        int partialSections,
        long unknownOrMissingSections,
        long knownPositions,
        long knownAirPositions,
        long knownNonAirPositions,
        int paletteEntries,
        int minimumPaletteSize,
        int maximumPaletteSize,
        int landmarkCount,
        Map<LandmarkType, Integer> landmarksByType,
        String geometrySha256
    ) {
        this.chunkCount = chunkCount;
        this.sectionCount = sectionCount;
        this.completeSections = completeSections;
        this.partialSections = partialSections;
        this.unknownOrMissingSections = unknownOrMissingSections;
        this.knownPositions = knownPositions;
        this.knownAirPositions = knownAirPositions;
        this.knownNonAirPositions = knownNonAirPositions;
        this.paletteEntries = paletteEntries;
        this.minimumPaletteSize = minimumPaletteSize;
        this.maximumPaletteSize = maximumPaletteSize;
        this.landmarkCount = landmarkCount;
        this.landmarksByType = Collections.unmodifiableMap(
            new EnumMap<LandmarkType, Integer>(landmarksByType)
        );
        this.geometrySha256 = geometrySha256;
    }

    public static CanonicalMapStatistics calculate(CanonicalMap map) {
        ValidationReport validation = new CanonicalMapValidator().validate(map);
        if (!validation.isValid()) {
            throw new IllegalArgumentException(
                "cannot calculate statistics for invalid map: " + validation.getErrors().get(0)
            );
        }
        int complete = 0;
        int partial = 0;
        int paletteEntries = 0;
        int minimumPalette = Integer.MAX_VALUE;
        int maximumPalette = 0;
        long known = 0L;
        long air = 0L;
        for (CanonicalChunk chunk : map.getChunks()) {
            for (CanonicalSection section : chunk.getSections()) {
                if (section.getCoverageStatus() == CoverageStatus.COMPLETE) complete++;
                if (section.getCoverageStatus() == CoverageStatus.PARTIAL) partial++;
                int paletteSize = section.getPalette().size();
                paletteEntries += paletteSize;
                minimumPalette = Math.min(minimumPalette, paletteSize);
                maximumPalette = Math.max(maximumPalette, paletteSize);
                for (int positionIndex = 0;
                    positionIndex < CanonicalSection.POSITION_COUNT;
                    positionIndex++) {
                    if (!section.isKnown(positionIndex)) continue;
                    known++;
                    if (section.getState(positionIndex).get().isAir()) air++;
                }
            }
        }
        MapBounds bounds = map.getManifest().getBounds();
        long chunkColumns = (long) Math.floorDiv(bounds.getMaxX(), 16)
            - Math.floorDiv(bounds.getMinX(), 16) + 1L;
        long chunkRows = (long) Math.floorDiv(bounds.getMaxZ(), 16)
            - Math.floorDiv(bounds.getMinZ(), 16) + 1L;
        long verticalSections = (long) Math.floorDiv(bounds.getMaxY(), 16)
            - Math.floorDiv(bounds.getMinY(), 16) + 1L;
        long expectedSections = Math.multiplyExact(
            Math.multiplyExact(chunkColumns, chunkRows),
            verticalSections
        );
        long knownCoverageSections = (long) complete + partial;
        long unknownOrMissing = Math.max(0L, expectedSections - knownCoverageSections);

        EnumMap<LandmarkType, Integer> byType =
            new EnumMap<LandmarkType, Integer>(LandmarkType.class);
        for (LandmarkType type : LandmarkType.values()) {
            byType.put(type, Integer.valueOf(0));
        }
        for (CanonicalLandmark landmark : map.getLandmarks()) {
            LandmarkType type = landmark.getType();
            byType.put(type, Integer.valueOf(byType.get(type).intValue() + 1));
        }
        return new CanonicalMapStatistics(
            map.getChunks().size(),
            countSections(map),
            complete,
            partial,
            unknownOrMissing,
            known,
            air,
            known - air,
            paletteEntries,
            minimumPalette == Integer.MAX_VALUE ? 0 : minimumPalette,
            maximumPalette,
            map.getLandmarks().size(),
            byType,
            new CanonicalGeometryHasher().sha256(map)
        );
    }

    private static int countSections(CanonicalMap map) {
        int total = 0;
        for (CanonicalChunk chunk : map.getChunks()) total += chunk.getSections().size();
        return total;
    }

    public int getChunkCount() { return chunkCount; }
    public int getSectionCount() { return sectionCount; }
    public int getCompleteSections() { return completeSections; }
    public int getPartialSections() { return partialSections; }
    public long getUnknownOrMissingSections() { return unknownOrMissingSections; }
    public long getKnownPositions() { return knownPositions; }
    public long getKnownAirPositions() { return knownAirPositions; }
    public long getKnownNonAirPositions() { return knownNonAirPositions; }
    public int getPaletteEntries() { return paletteEntries; }
    public int getMinimumPaletteSize() { return minimumPaletteSize; }
    public int getMaximumPaletteSize() { return maximumPaletteSize; }
    public int getLandmarkCount() { return landmarkCount; }
    public Map<LandmarkType, Integer> getLandmarksByType() { return landmarksByType; }
    public String getGeometrySha256() { return geometrySha256; }
}
