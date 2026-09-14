package com.bedwarsbot.world.canonical;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

public final class CanonicalMapValidator {
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern SHA_256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    public ValidationReport validate(CanonicalMap map) {
        List<ValidationIssue> errors = new ArrayList<ValidationIssue>();
        if (map == null) {
            add(errors, "MAP_REQUIRED", "$", "canonical map must not be null");
            return new ValidationReport(errors);
        }
        CanonicalMapManifest manifest = map.getManifest();
        validateManifest(manifest, errors);
        MapBounds bounds = manifest == null ? null : manifest.getBounds();
        validateChunks(map.getChunks(), bounds, errors);
        validateLandmarks(map.getLandmarks(), bounds, errors);
        return new ValidationReport(errors);
    }

    private static void validateManifest(
        CanonicalMapManifest manifest,
        List<ValidationIssue> errors
    ) {
        if (manifest == null) {
            add(errors, "MANIFEST_REQUIRED", "manifest", "manifest must not be null");
            return;
        }
        if (manifest.getSchemaVersion() != CanonicalMapManifest.SUPPORTED_SCHEMA_VERSION) {
            add(errors, "UNSUPPORTED_SCHEMA", "manifest.schema_version",
                "only canonical map schema version 1 is supported");
        }
        requiredId(errors, "manifest.map_id", manifest.getMapId());
        required(errors, "manifest.display_name", manifest.getDisplayName());
        requiredId(errors, "manifest.map_revision", manifest.getMapRevision());
        required(errors, "manifest.bedwars_mode", manifest.getBedwarsMode());
        if (!"1.8.9".equals(manifest.getMinecraftVersion())) {
            add(errors, "UNSUPPORTED_MINECRAFT_VERSION", "manifest.minecraft_version",
                "schema version 1 requires Minecraft 1.8.9");
        }
        if (manifest.getDimension() < -1 || manifest.getDimension() > 1) {
            add(errors, "INVALID_DIMENSION", "manifest.dimension",
                "schema version 1 supports vanilla dimensions -1, 0, and 1");
        }
        MapBounds bounds = manifest.getBounds();
        if (bounds == null || !bounds.isOrdered()) {
            add(errors, "INVALID_BOUNDS", "manifest.bounds",
                "minimum coordinates must not exceed maximum coordinates");
        } else if (bounds.getMinY() < 0 || bounds.getMaxY() > 255) {
            add(errors, "INVALID_BOUNDS_Y", "manifest.bounds",
                "Minecraft 1.8.9 block Y bounds must be within 0..255");
        }
        required(errors, "manifest.source_provenance", manifest.getSourceProvenance());
        if (manifest.getAcquisitionDate() != null) {
            try {
                LocalDate.parse(manifest.getAcquisitionDate());
            } catch (DateTimeParseException invalid) {
                add(errors, "INVALID_ACQUISITION_DATE", "manifest.acquisition_date",
                    "date must be null or a valid ISO YYYY-MM-DD date");
            }
        }
        if (manifest.getSourceChecksum() != null
            && !SHA_256_PATTERN.matcher(manifest.getSourceChecksum()).matches()) {
            add(errors, "INVALID_SOURCE_CHECKSUM", "manifest.source_checksum",
                "source checksum must be a lowercase SHA-256 hex string");
        }
        if (manifest.getValidationStatus() == null) {
            add(errors, "VALIDATION_STATUS_REQUIRED", "manifest.validation_status",
                "validation status must be explicit");
        }
        if (!"palette-section-json".equals(manifest.getGeometryFormat())
            || manifest.getGeometryFormatVersion()
                != CanonicalMapManifest.SUPPORTED_GEOMETRY_FORMAT_VERSION) {
            add(errors, "UNSUPPORTED_GEOMETRY_FORMAT", "manifest.geometry_format",
                "only palette-section-json version 1 is supported");
        }
        if (!"landmarks-json".equals(manifest.getLandmarkFormat())
            || manifest.getLandmarkFormatVersion()
                != CanonicalMapManifest.SUPPORTED_LANDMARK_FORMAT_VERSION) {
            add(errors, "UNSUPPORTED_LANDMARK_FORMAT", "manifest.landmark_format",
                "only landmarks-json version 1 is supported");
        }
    }

    private static void validateChunks(
        List<CanonicalChunk> chunks,
        MapBounds bounds,
        List<ValidationIssue> errors
    ) {
        if (chunks == null) {
            add(errors, "CHUNKS_REQUIRED", "geometry", "chunk list must not be null");
            return;
        }
        if (chunks.size() > CanonicalMapLimits.MAX_GEOMETRY_FILES) {
            add(errors, "TOO_MANY_CHUNKS", "geometry",
                "chunk count exceeds " + CanonicalMapLimits.MAX_GEOMETRY_FILES);
        }
        Set<String> chunkKeys = new HashSet<String>();
        for (int chunkNumber = 0; chunkNumber < chunks.size(); chunkNumber++) {
            CanonicalChunk chunk = chunks.get(chunkNumber);
            String path = "geometry.chunks[" + chunkNumber + ']';
            if (chunk == null) {
                add(errors, "CHUNK_REQUIRED", path, "chunk must not be null");
                continue;
            }
            String key = chunk.getChunkX() + "," + chunk.getChunkZ();
            if (!chunkKeys.add(key)) {
                add(errors, "DUPLICATE_CHUNK", path, "chunk coordinates repeat: " + key);
            }
            if (bounds != null && bounds.isOrdered()
                && !bounds.intersectsChunk(chunk.getChunkX(), chunk.getChunkZ())) {
                add(errors, "CHUNK_OUT_OF_BOUNDS", path,
                    "chunk does not intersect declared geometry bounds");
            }
            validateSections(chunk, path, bounds, errors);
        }
    }

    private static void validateSections(
        CanonicalChunk chunk,
        String chunkPath,
        MapBounds bounds,
        List<ValidationIssue> errors
    ) {
        List<CanonicalSection> sections = chunk.getSections();
        if (sections == null) {
            add(errors, "SECTIONS_REQUIRED", chunkPath + ".sections",
                "section list must not be null");
            return;
        }
        if (sections.size() > CanonicalMapLimits.MAX_SECTIONS_PER_CHUNK) {
            add(errors, "TOO_MANY_SECTIONS", chunkPath + ".sections",
                "section count exceeds " + CanonicalMapLimits.MAX_SECTIONS_PER_CHUNK);
        }
        Set<Integer> indices = new HashSet<Integer>();
        for (int sectionNumber = 0; sectionNumber < sections.size(); sectionNumber++) {
            CanonicalSection section = sections.get(sectionNumber);
            String path = chunkPath + ".sections[" + sectionNumber + ']';
            if (section == null) {
                add(errors, "SECTION_REQUIRED", path, "section must not be null");
                continue;
            }
            int sectionIndex = section.getSectionIndex();
            if (sectionIndex < 0 || sectionIndex > 15) {
                add(errors, "INVALID_SECTION_INDEX", path + ".section_index",
                    "section index must be within 0..15");
            }
            if (!indices.add(Integer.valueOf(sectionIndex))) {
                add(errors, "DUPLICATE_SECTION", path,
                    "section index repeats: " + sectionIndex);
            }
            if (bounds != null && bounds.isOrdered()
                && !bounds.intersectsSection(sectionIndex)) {
                add(errors, "SECTION_OUT_OF_BOUNDS", path,
                    "section does not intersect declared geometry bounds");
            }
            validateSectionData(chunk, section, path, bounds, errors);
        }
    }

    private static void validateSectionData(
        CanonicalChunk chunk,
        CanonicalSection section,
        String path,
        MapBounds bounds,
        List<ValidationIssue> errors
    ) {
        CoverageStatus coverage = section.getCoverageStatus();
        List<CanonicalBlockState> palette = section.getPalette();
        int[] indices = section.copyPaletteIndices();
        if (coverage == null) {
            add(errors, "COVERAGE_REQUIRED", path + ".coverage", "coverage is required");
        }
        if (palette == null) {
            add(errors, "PALETTE_REQUIRED", path + ".palette", "palette must not be null");
        } else {
            if (palette.size() > CanonicalMapLimits.MAX_PALETTE_ENTRIES) {
                add(errors, "TOO_MANY_PALETTE_ENTRIES", path + ".palette",
                    "palette entry count exceeds "
                        + CanonicalMapLimits.MAX_PALETTE_ENTRIES);
            }
            Set<CanonicalBlockState> uniqueStates = new HashSet<CanonicalBlockState>();
            for (int paletteIndex = 0; paletteIndex < palette.size(); paletteIndex++) {
                CanonicalBlockState state = palette.get(paletteIndex);
                if (state == null) {
                    add(errors, "PALETTE_STATE_REQUIRED",
                        path + ".palette[" + paletteIndex + ']', "state must not be null");
                    continue;
                }
                if (state.getBlockId() < 0 || state.getBlockId() > 4095
                    || state.getMetadata() < 0 || state.getMetadata() > 15) {
                    add(errors, "INVALID_BLOCK_STATE",
                        path + ".palette[" + paletteIndex + ']',
                        "block ID must be 0..4095 and metadata must be 0..15");
                }
                if (!uniqueStates.add(state)) {
                    add(errors, "DUPLICATE_PALETTE_STATE",
                        path + ".palette[" + paletteIndex + ']',
                        "palette states must be unique");
                }
            }
        }
        if (indices == null || indices.length != CanonicalSection.POSITION_COUNT) {
            add(errors, "INVALID_SECTION_LENGTH", path + ".palette_indices",
                "section must contain exactly 4096 position entries");
            return;
        }
        int known = 0;
        for (int positionIndex = 0; positionIndex < indices.length; positionIndex++) {
            int paletteIndex = indices[positionIndex];
            if (paletteIndex == CanonicalSection.UNKNOWN_PALETTE_INDEX) continue;
            known++;
            if (palette == null || paletteIndex < 0 || paletteIndex >= palette.size()) {
                add(errors, "INVALID_PALETTE_INDEX",
                    path + ".palette_indices[" + positionIndex + ']',
                    "palette index " + paletteIndex + " is invalid");
            }
            if (bounds != null && bounds.isOrdered()) {
                int localX = positionIndex & 15;
                int localZ = positionIndex >> 4 & 15;
                int localY = positionIndex >> 8 & 15;
                long worldX = (long) chunk.getChunkX() * 16L + localX;
                long worldY = (long) section.getSectionIndex() * 16L + localY;
                long worldZ = (long) chunk.getChunkZ() * 16L + localZ;
                if (worldX < Integer.MIN_VALUE || worldX > Integer.MAX_VALUE
                    || worldY < Integer.MIN_VALUE || worldY > Integer.MAX_VALUE
                    || worldZ < Integer.MIN_VALUE || worldZ > Integer.MAX_VALUE
                    || !bounds.contains((int) worldX, (int) worldY, (int) worldZ)) {
                    add(errors, "KNOWN_POSITION_OUT_OF_BOUNDS",
                        path + ".palette_indices[" + positionIndex + ']',
                        "known position lies outside declared bounds");
                }
            }
        }
        if (known > 0 && (palette == null || palette.isEmpty())) {
            add(errors, "EMPTY_PALETTE", path + ".palette",
                "a section with known positions requires a nonempty palette");
        }
        if (coverage == CoverageStatus.UNKNOWN
            && palette != null && !palette.isEmpty()) {
            add(errors, "UNKNOWN_COVERAGE_HAS_PALETTE", path + ".palette",
                "UNKNOWN coverage must not carry a block-state palette");
        }
        if (coverage == CoverageStatus.UNKNOWN && known != 0) {
            add(errors, "UNKNOWN_COVERAGE_HAS_DATA", path + ".coverage",
                "UNKNOWN coverage must contain no known positions");
        } else if (coverage == CoverageStatus.PARTIAL
            && (known <= 0 || known >= CanonicalSection.POSITION_COUNT)) {
            add(errors, "INVALID_PARTIAL_COVERAGE", path + ".coverage",
                "PARTIAL coverage must contain between 1 and 4095 known positions");
        } else if (coverage == CoverageStatus.COMPLETE
            && known != CanonicalSection.POSITION_COUNT) {
            add(errors, "INCOMPLETE_COMPLETE_SECTION", path + ".coverage",
                "COMPLETE coverage requires all 4096 positions");
        }
    }

    private static void validateLandmarks(
        List<CanonicalLandmark> landmarks,
        MapBounds bounds,
        List<ValidationIssue> errors
    ) {
        if (landmarks == null) {
            add(errors, "LANDMARKS_REQUIRED", "landmarks", "landmark list must not be null");
            return;
        }
        if (landmarks.size() > CanonicalMapLimits.MAX_LANDMARKS) {
            add(errors, "TOO_MANY_LANDMARKS", "landmarks",
                "landmark count exceeds " + CanonicalMapLimits.MAX_LANDMARKS);
        }
        Map<String, CanonicalLandmark> byId = new HashMap<String, CanonicalLandmark>();
        for (int index = 0; index < landmarks.size(); index++) {
            CanonicalLandmark landmark = landmarks.get(index);
            String path = "landmarks[" + index + ']';
            if (landmark == null) {
                add(errors, "LANDMARK_REQUIRED", path, "landmark must not be null");
                continue;
            }
            requiredId(errors, path + ".landmark_id", landmark.getLandmarkId());
            if (landmark.getType() == null) {
                add(errors, "LANDMARK_TYPE_REQUIRED", path + ".type", "type is required");
            }
            if (landmark.getPosition() == null) {
                add(errors, "LANDMARK_POSITION_REQUIRED", path + ".position",
                    "position is required");
            } else if (bounds != null && bounds.isOrdered()
                && !bounds.contains(
                    landmark.getPosition().getX(),
                    landmark.getPosition().getY(),
                    landmark.getPosition().getZ())) {
                add(errors, "LANDMARK_OUT_OF_BOUNDS", path + ".position",
                    "landmark lies outside declared bounds");
            }
            if (!Double.isFinite(landmark.getConfidence())
                || landmark.getConfidence() < 0.0
                || landmark.getConfidence() > 1.0) {
                add(errors, "INVALID_LANDMARK_CONFIDENCE", path + ".confidence",
                    "confidence must be finite and within 0..1");
            }
            required(errors, path + ".provenance", landmark.getProvenance());
            if (landmark.getValidationStatus() == null) {
                add(errors, "LANDMARK_VALIDATION_STATUS_REQUIRED",
                    path + ".validation_status", "validation status is required");
            }
            CanonicalLandmark previous = byId.put(landmark.getLandmarkId(), landmark);
            if (previous != null) {
                add(errors,
                    previous.equals(landmark)
                        ? "DUPLICATE_LANDMARK_ID"
                        : "CONFLICTING_LANDMARK",
                    path + ".landmark_id",
                    "landmark ID repeats: " + landmark.getLandmarkId());
            }
        }
    }

    private static void requiredId(
        List<ValidationIssue> errors,
        String path,
        String value
    ) {
        if (value == null || !ID_PATTERN.matcher(value).matches()) {
            add(errors, "INVALID_ID", path,
                "ID must match [a-z0-9][a-z0-9._-]*");
        }
    }

    private static void required(
        List<ValidationIssue> errors,
        String path,
        String value
    ) {
        if (value == null || value.trim().isEmpty()) {
            add(errors, "REQUIRED_VALUE", path, "value must not be empty");
        }
    }

    private static void add(
        List<ValidationIssue> errors,
        String code,
        String path,
        String message
    ) {
        errors.add(new ValidationIssue(code, path, message));
    }
}
