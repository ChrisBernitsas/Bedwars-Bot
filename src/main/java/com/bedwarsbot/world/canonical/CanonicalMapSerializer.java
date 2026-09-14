package com.bedwarsbot.world.canonical;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CanonicalMapSerializer {
    public static final String MANIFEST_FILE = "manifest.json";
    public static final String LANDMARKS_FILE = "landmarks.json";

    public void save(CanonicalMap map, Path directory) throws IOException {
        ValidationReport validation = new CanonicalMapValidator().validate(map);
        if (!validation.isValid()) throw new CanonicalMapFormatException(validation);
        requireSafeDirectory(directory);
        Files.createDirectories(directory);
        Path geometryDirectory = directory.resolve("geometry");
        if (Files.exists(geometryDirectory, LinkOption.NOFOLLOW_LINKS)
            && (Files.isSymbolicLink(geometryDirectory)
                || !Files.isDirectory(geometryDirectory, LinkOption.NOFOLLOW_LINKS))) {
            throw format("UNSAFE_PATH", geometryDirectory.toString(),
                "geometry path must be a real directory, not a symlink");
        }
        Files.createDirectories(geometryDirectory);

        List<String> geometryFiles = new ArrayList<String>();
        for (CanonicalChunk chunk : map.getChunks()) {
            String relative = "geometry/chunk_" + chunk.getChunkX()
                + '_' + chunk.getChunkZ() + ".json";
            geometryFiles.add(relative);
            writeAtomic(directory.resolve(relative), CanonicalJson.write(chunkJson(map, chunk)));
        }
        writeAtomic(directory.resolve(LANDMARKS_FILE),
            CanonicalJson.write(landmarksJson(map)));
        String geometryHash = new CanonicalGeometryHasher().sha256(map);
        writeAtomic(directory.resolve(MANIFEST_FILE),
            CanonicalJson.write(manifestJson(map.getManifest(), geometryFiles, geometryHash)));
    }

    public CanonicalMap load(Path directory) throws IOException {
        requireReadableRoot(directory);
        LoadBudget loadBudget = new LoadBudget();
        Path manifestPath = directory.resolve(MANIFEST_FILE);
        ManifestData manifestData = parseManifest(
            readObject(manifestPath, loadBudget),
            MANIFEST_FILE
        );
        if (manifestData.manifest.getSchemaVersion()
            != CanonicalMapManifest.SUPPORTED_SCHEMA_VERSION) {
            throw format("UNSUPPORTED_SCHEMA", "manifest.schema_version",
                "only canonical map schema version 1 is supported");
        }
        List<CanonicalChunk> chunks = new ArrayList<CanonicalChunk>();
        Set<String> listedFiles = new HashSet<String>();
        for (String relativeFile : manifestData.geometryFiles) {
            if (!listedFiles.add(relativeFile)) {
                throw format("DUPLICATE_GEOMETRY_FILE", "manifest.geometry_files",
                    "geometry file is listed more than once: " + relativeFile);
            }
            Path geometryPath = resolveContainedFile(directory, relativeFile);
            chunks.add(parseChunk(
                readObject(geometryPath, loadBudget),
                relativeFile,
                manifestData.manifest
            ));
        }
        Path landmarksPath = resolveContainedFile(directory, manifestData.landmarksFile);
        List<CanonicalLandmark> landmarks = parseLandmarks(
            readObject(landmarksPath, loadBudget),
            manifestData.landmarksFile,
            manifestData.manifest
        );
        CanonicalMap map = new CanonicalMap(manifestData.manifest, chunks, landmarks);
        ValidationReport validation = new CanonicalMapValidator().validate(map);
        if (!validation.isValid()) throw new CanonicalMapFormatException(validation);
        String actualHash = new CanonicalGeometryHasher().sha256(map);
        if (!actualHash.equals(manifestData.geometrySha256)) {
            throw format("GEOMETRY_HASH_MISMATCH", "manifest.geometry_sha256",
                "expected " + manifestData.geometrySha256 + " but calculated " + actualHash);
        }
        return map;
    }

    private static Map<String, Object> manifestJson(
        CanonicalMapManifest manifest,
        List<String> geometryFiles,
        String geometryHash
    ) {
        LinkedHashMap<String, Object> json = object();
        json.put("schema_version", manifest.getSchemaVersion());
        json.put("map_id", manifest.getMapId());
        json.put("display_name", manifest.getDisplayName());
        json.put("map_revision", manifest.getMapRevision());
        json.put("bedwars_mode", manifest.getBedwarsMode());
        json.put("minecraft_version", manifest.getMinecraftVersion());
        json.put("dimension", manifest.getDimension());
        MapBounds bounds = manifest.getBounds();
        LinkedHashMap<String, Object> boundsJson = object();
        boundsJson.put("min_x", bounds.getMinX());
        boundsJson.put("min_y", bounds.getMinY());
        boundsJson.put("min_z", bounds.getMinZ());
        boundsJson.put("max_x", bounds.getMaxX());
        boundsJson.put("max_y", bounds.getMaxY());
        boundsJson.put("max_z", bounds.getMaxZ());
        json.put("bounds", boundsJson);
        json.put("source_provenance", manifest.getSourceProvenance());
        json.put("acquisition_date", manifest.getAcquisitionDate());
        json.put("source_reference", manifest.getSourceReference());
        json.put("source_checksum", manifest.getSourceChecksum());
        json.put("validation_status", manifest.getValidationStatus().name());
        json.put("notes", manifest.getNotes());
        json.put("geometry_format", manifest.getGeometryFormat());
        json.put("geometry_format_version", manifest.getGeometryFormatVersion());
        json.put("landmark_format", manifest.getLandmarkFormat());
        json.put("landmark_format_version", manifest.getLandmarkFormatVersion());
        json.put("landmarks_file", LANDMARKS_FILE);
        json.put("geometry_files", new ArrayList<String>(geometryFiles));
        json.put("geometry_sha256", geometryHash);
        return json;
    }

    private static Map<String, Object> chunkJson(CanonicalMap map, CanonicalChunk chunk) {
        LinkedHashMap<String, Object> json = object();
        json.put("schema_version", map.getManifest().getSchemaVersion());
        json.put("geometry_format_version",
            map.getManifest().getGeometryFormatVersion());
        json.put("dimension", map.getManifest().getDimension());
        json.put("chunk_x", chunk.getChunkX());
        json.put("chunk_z", chunk.getChunkZ());
        List<Object> sections = new ArrayList<Object>();
        for (CanonicalSection section : chunk.getSections()) {
            sections.add(sectionJson(section));
        }
        json.put("sections", sections);
        return json;
    }

    private static Map<String, Object> sectionJson(CanonicalSection section) {
        LinkedHashMap<String, Object> json = object();
        json.put("section_index", section.getSectionIndex());
        json.put("coverage", section.getCoverageStatus().name());
        List<Object> paletteJson = new ArrayList<Object>();
        for (CanonicalBlockState state : section.getPalette()) {
            LinkedHashMap<String, Object> stateJson = object();
            stateJson.put("block_id", state.getBlockId());
            stateJson.put("metadata", state.getMetadata());
            paletteJson.add(stateJson);
        }
        json.put("palette", paletteJson);

        int[] indices = section.copyPaletteIndices();
        if (section.getCoverageStatus() == CoverageStatus.UNKNOWN) {
            json.put("state_encoding", "none_v1");
            json.put("state_data", "");
            return json;
        }
        if (section.getCoverageStatus() == CoverageStatus.PARTIAL) {
            BitSet coverage = new BitSet(CanonicalSection.POSITION_COUNT);
            for (int index = 0; index < indices.length; index++) {
                if (indices[index] != CanonicalSection.UNKNOWN_PALETTE_INDEX) {
                    coverage.set(index);
                }
            }
            json.put("coverage_encoding", "bitset_lsb0_base64_v1");
            json.put("coverage_data",
                Base64.getEncoder().encodeToString(coverage.toByteArray()));
        }
        List<Integer> knownIndices = new ArrayList<Integer>();
        for (int paletteIndex : indices) {
            if (paletteIndex != CanonicalSection.UNKNOWN_PALETTE_INDEX) {
                knownIndices.add(Integer.valueOf(paletteIndex));
            }
        }
        boolean uniform = true;
        int first = knownIndices.get(0).intValue();
        for (Integer index : knownIndices) {
            if (index.intValue() != first) {
                uniform = false;
                break;
            }
        }
        if (uniform) {
            json.put("state_encoding", "uniform_palette_index_v1");
            json.put("state_data", Integer.toString(first));
        } else {
            boolean oneByte = section.getPalette().size() <= 256;
            json.put("state_encoding", oneByte
                ? "u8_palette_indices_base64_v1"
                : "u16be_palette_indices_base64_v1");
            json.put("state_data", Base64.getEncoder().encodeToString(
                packIndices(knownIndices, oneByte)
            ));
        }
        return json;
    }

    private static byte[] packIndices(List<Integer> indices, boolean oneByte) {
        ByteBuffer buffer = ByteBuffer.allocate(indices.size() * (oneByte ? 1 : 2));
        buffer.order(ByteOrder.BIG_ENDIAN);
        for (Integer index : indices) {
            if (oneByte) buffer.put((byte) index.intValue());
            else buffer.putShort((short) index.intValue());
        }
        return buffer.array();
    }

    private static Map<String, Object> landmarksJson(CanonicalMap map) {
        LinkedHashMap<String, Object> json = object();
        json.put("schema_version", map.getManifest().getSchemaVersion());
        json.put("landmark_format_version",
            map.getManifest().getLandmarkFormatVersion());
        List<Object> landmarks = new ArrayList<Object>();
        for (CanonicalLandmark landmark : map.getLandmarks()) {
            LinkedHashMap<String, Object> value = object();
            value.put("landmark_id", landmark.getLandmarkId());
            value.put("type", landmark.getType().name());
            LinkedHashMap<String, Object> position = object();
            position.put("x", landmark.getPosition().getX());
            position.put("y", landmark.getPosition().getY());
            position.put("z", landmark.getPosition().getZ());
            value.put("position", position);
            value.put("facing", landmark.getFacing() == null
                ? null : landmark.getFacing().name());
            value.put("team", landmark.getTeam());
            value.put("display_name", landmark.getDisplayName());
            value.put("confidence", landmark.getConfidence());
            value.put("provenance", landmark.getProvenance());
            value.put("validation_status", landmark.getValidationStatus().name());
            value.put("notes", landmark.getNotes());
            landmarks.add(value);
        }
        json.put("landmarks", landmarks);
        return json;
    }

    private static ManifestData parseManifest(Map<String, Object> json, String path)
        throws CanonicalMapFormatException {
        int schemaVersion = integer(json, "schema_version", path);
        Map<String, Object> bounds = object(json, "bounds", path);
        CanonicalMapManifest manifest = new CanonicalMapManifest(
            schemaVersion,
            string(json, "map_id", path),
            string(json, "display_name", path),
            string(json, "map_revision", path),
            string(json, "bedwars_mode", path),
            string(json, "minecraft_version", path),
            integer(json, "dimension", path),
            new MapBounds(
                integer(bounds, "min_x", path + ".bounds"),
                integer(bounds, "min_y", path + ".bounds"),
                integer(bounds, "min_z", path + ".bounds"),
                integer(bounds, "max_x", path + ".bounds"),
                integer(bounds, "max_y", path + ".bounds"),
                integer(bounds, "max_z", path + ".bounds")
            ),
            string(json, "source_provenance", path),
            nullableString(json, "acquisition_date", path),
            nullableString(json, "source_reference", path),
            nullableString(json, "source_checksum", path),
            enumValue(ValidationStatus.class,
                string(json, "validation_status", path), path + ".validation_status"),
            nullableString(json, "notes", path),
            string(json, "geometry_format", path),
            integer(json, "geometry_format_version", path),
            string(json, "landmark_format", path),
            integer(json, "landmark_format_version", path)
        );
        String landmarksFile = string(json, "landmarks_file", path);
        List<Object> files = array(json, "geometry_files", path);
        if (files.size() > CanonicalMapLimits.MAX_GEOMETRY_FILES) {
            throw format("TOO_MANY_GEOMETRY_FILES", path + ".geometry_files",
                "geometry file count exceeds "
                    + CanonicalMapLimits.MAX_GEOMETRY_FILES);
        }
        List<String> geometryFiles = new ArrayList<String>();
        for (int index = 0; index < files.size(); index++) {
            if (!(files.get(index) instanceof String)) {
                throw format("INVALID_FIELD_TYPE",
                    path + ".geometry_files[" + index + ']', "expected string");
            }
            geometryFiles.add((String) files.get(index));
        }
        String hash = string(json, "geometry_sha256", path);
        if (!hash.matches("[0-9a-f]{64}")) {
            throw format("INVALID_GEOMETRY_HASH", path + ".geometry_sha256",
                "geometry hash must be lowercase SHA-256 hex");
        }
        return new ManifestData(manifest, landmarksFile, geometryFiles, hash);
    }

    private static CanonicalChunk parseChunk(
        Map<String, Object> json,
        String path,
        CanonicalMapManifest manifest
    ) throws CanonicalMapFormatException {
        requireVersion(json, "schema_version", manifest.getSchemaVersion(), path);
        requireVersion(json, "geometry_format_version",
            manifest.getGeometryFormatVersion(), path);
        requireVersion(json, "dimension", manifest.getDimension(), path);
        int chunkX = integer(json, "chunk_x", path);
        int chunkZ = integer(json, "chunk_z", path);
        List<Object> values = array(json, "sections", path);
        if (values.size() > CanonicalMapLimits.MAX_SECTIONS_PER_CHUNK) {
            throw format("TOO_MANY_SECTIONS", path + ".sections",
                "section count exceeds " + CanonicalMapLimits.MAX_SECTIONS_PER_CHUNK);
        }
        List<CanonicalSection> sections = new ArrayList<CanonicalSection>();
        for (int index = 0; index < values.size(); index++) {
            sections.add(parseSection(asObject(values.get(index),
                path + ".sections[" + index + ']'),
                path + ".sections[" + index + ']'));
        }
        return new CanonicalChunk(chunkX, chunkZ, sections);
    }

    private static CanonicalSection parseSection(Map<String, Object> json, String path)
        throws CanonicalMapFormatException {
        int sectionIndex = integer(json, "section_index", path);
        CoverageStatus coverage = enumValue(CoverageStatus.class,
            string(json, "coverage", path), path + ".coverage");
        List<Object> paletteValues = array(json, "palette", path);
        if (paletteValues.size() > CanonicalMapLimits.MAX_PALETTE_ENTRIES) {
            throw format("TOO_MANY_PALETTE_ENTRIES", path + ".palette",
                "palette entry count exceeds "
                    + CanonicalMapLimits.MAX_PALETTE_ENTRIES);
        }
        List<CanonicalBlockState> palette = new ArrayList<CanonicalBlockState>();
        for (int index = 0; index < paletteValues.size(); index++) {
            Map<String, Object> state = asObject(paletteValues.get(index),
                path + ".palette[" + index + ']');
            palette.add(new CanonicalBlockState(
                integer(state, "block_id", path + ".palette[" + index + ']'),
                integer(state, "metadata", path + ".palette[" + index + ']')
            ));
        }
        int[] indices = new int[CanonicalSection.POSITION_COUNT];
        java.util.Arrays.fill(indices, CanonicalSection.UNKNOWN_PALETTE_INDEX);
        String encoding = string(json, "state_encoding", path);
        String stateData = string(json, "state_data", path);
        if (coverage == CoverageStatus.UNKNOWN) {
            if (!"none_v1".equals(encoding) || !stateData.isEmpty()) {
                throw format("INVALID_UNKNOWN_ENCODING", path,
                    "UNKNOWN section must use empty none_v1 state data");
            }
            return new CanonicalSection(sectionIndex, coverage, palette, indices);
        }

        BitSet known = new BitSet(CanonicalSection.POSITION_COUNT);
        if (coverage == CoverageStatus.COMPLETE) {
            known.set(0, CanonicalSection.POSITION_COUNT);
        } else {
            if (!"bitset_lsb0_base64_v1".equals(
                string(json, "coverage_encoding", path))) {
                throw format("UNSUPPORTED_COVERAGE_ENCODING", path + ".coverage_encoding",
                    "partial coverage requires bitset_lsb0_base64_v1");
            }
            byte[] coverageBytes = decodeBase64(
                string(json, "coverage_data", path), path + ".coverage_data");
            if (coverageBytes.length > CanonicalSection.POSITION_COUNT / 8) {
                throw format("INVALID_COVERAGE_DATA", path + ".coverage_data",
                    "coverage bitmap exceeds 4096 positions");
            }
            known = BitSet.valueOf(coverageBytes);
        }
        int knownCount = known.cardinality();
        int[] decoded = decodeStateIndices(encoding, stateData, knownCount, path);
        int decodedIndex = 0;
        for (int position = known.nextSetBit(0);
            position >= 0;
            position = known.nextSetBit(position + 1)) {
            indices[position] = decoded[decodedIndex++];
        }
        return new CanonicalSection(sectionIndex, coverage, palette, indices);
    }

    private static int[] decodeStateIndices(
        String encoding,
        String data,
        int count,
        String path
    ) throws CanonicalMapFormatException {
        int[] indices = new int[count];
        if ("uniform_palette_index_v1".equals(encoding)) {
            int paletteIndex;
            try {
                paletteIndex = Integer.parseInt(data);
            } catch (NumberFormatException invalid) {
                throw format("INVALID_STATE_DATA", path + ".state_data",
                    "uniform palette index must be an integer");
            }
            java.util.Arrays.fill(indices, paletteIndex);
            return indices;
        }
        byte[] bytes = decodeBase64(data, path + ".state_data");
        if ("u8_palette_indices_base64_v1".equals(encoding)) {
            if (bytes.length != count) {
                throw format("TRUNCATED_STATE_DATA", path + ".state_data",
                    "expected " + count + " bytes, found " + bytes.length);
            }
            for (int index = 0; index < count; index++) indices[index] = bytes[index] & 255;
            return indices;
        }
        if ("u16be_palette_indices_base64_v1".equals(encoding)) {
            if (bytes.length != count * 2) {
                throw format("TRUNCATED_STATE_DATA", path + ".state_data",
                    "expected " + count * 2 + " bytes, found " + bytes.length);
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            for (int index = 0; index < count; index++) {
                indices[index] = buffer.getShort() & 0xffff;
            }
            return indices;
        }
        throw format("UNSUPPORTED_STATE_ENCODING", path + ".state_encoding",
            "unsupported state encoding: " + encoding);
    }

    private static List<CanonicalLandmark> parseLandmarks(
        Map<String, Object> json,
        String path,
        CanonicalMapManifest manifest
    ) throws CanonicalMapFormatException {
        requireVersion(json, "schema_version", manifest.getSchemaVersion(), path);
        requireVersion(json, "landmark_format_version",
            manifest.getLandmarkFormatVersion(), path);
        List<Object> values = array(json, "landmarks", path);
        if (values.size() > CanonicalMapLimits.MAX_LANDMARKS) {
            throw format("TOO_MANY_LANDMARKS", path + ".landmarks",
                "landmark count exceeds " + CanonicalMapLimits.MAX_LANDMARKS);
        }
        List<CanonicalLandmark> landmarks = new ArrayList<CanonicalLandmark>();
        for (int index = 0; index < values.size(); index++) {
            String itemPath = path + ".landmarks[" + index + ']';
            Map<String, Object> value = asObject(values.get(index), itemPath);
            Map<String, Object> position = object(value, "position", itemPath);
            String facingName = nullableString(value, "facing", itemPath);
            landmarks.add(new CanonicalLandmark(
                string(value, "landmark_id", itemPath),
                enumValue(LandmarkType.class,
                    string(value, "type", itemPath), itemPath + ".type"),
                new CanonicalPosition(
                    integer(position, "x", itemPath + ".position"),
                    integer(position, "y", itemPath + ".position"),
                    integer(position, "z", itemPath + ".position")
                ),
                facingName == null ? null
                    : enumValue(Facing.class, facingName, itemPath + ".facing"),
                nullableString(value, "team", itemPath),
                nullableString(value, "display_name", itemPath),
                number(value, "confidence", itemPath),
                string(value, "provenance", itemPath),
                enumValue(ValidationStatus.class,
                    string(value, "validation_status", itemPath),
                    itemPath + ".validation_status"),
                nullableString(value, "notes", itemPath)
            ));
        }
        return landmarks;
    }

    private static Map<String, Object> readObject(Path path, LoadBudget loadBudget)
        throws IOException {
        if (Files.isSymbolicLink(path)
            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw format("MISSING_FILE", path.toString(), "required file is missing or unsafe");
        }
        long size = Files.size(path);
        if (size > CanonicalMapLimits.MAX_JSON_FILE_BYTES) {
            throw format("FILE_TOO_LARGE", path.toString(),
                "JSON file exceeds " + CanonicalMapLimits.MAX_JSON_FILE_BYTES + " bytes");
        }
        byte[] bytes;
        try {
            bytes = readLimitedBytes(path);
        } catch (CanonicalMapFormatException invalid) {
            throw invalid;
        } catch (IOException failure) {
            throw new CanonicalMapFormatException(
                "READ_FAILURE", path.toString(), failure.getMessage(), failure
            );
        }
        loadBudget.accountBytes(bytes.length, path.toString());
        String content = decodeUtf8(bytes, path.toString());
        return asObject(CanonicalJson.parse(content, path.toString()), path.toString());
    }

    private static byte[] readLimitedBytes(Path path) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0L;
        try (InputStream input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > CanonicalMapLimits.MAX_JSON_FILE_BYTES) {
                    throw format("FILE_TOO_LARGE", path.toString(),
                        "JSON file exceeds "
                            + CanonicalMapLimits.MAX_JSON_FILE_BYTES + " bytes");
                }
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    private static String decodeUtf8(byte[] bytes, String path)
        throws CanonicalMapFormatException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException invalid) {
            throw format("INVALID_UTF8", path, "JSON file is not valid UTF-8");
        }
    }

    private static void writeAtomic(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
            || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(path)) {
            throw format("UNSAFE_PATH", path.toString(),
                "output must be a regular path inside a real directory");
        }
        Path temporary = Files.createTempFile(
            parent,
            path.getFileName().toString() + '.',
            ".tmp"
        );
        try {
            Files.write(temporary, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, path,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requireSafeDirectory(Path directory) throws CanonicalMapFormatException {
        if (directory == null || !directory.isAbsolute()) {
            throw format("UNSAFE_PATH", "directory", "map directory must be absolute");
        }
        if (directory.normalize().getParent() == null) {
            throw format("UNSAFE_PATH", directory.toString(),
                "filesystem root cannot be used as a map directory");
        }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
            && (Files.isSymbolicLink(directory) || !Files.isDirectory(directory))) {
            throw format("UNSAFE_PATH", directory.toString(),
                "map directory must be a real directory, not a symlink");
        }
    }

    private static void requireReadableRoot(Path directory)
        throws CanonicalMapFormatException {
        requireSafeDirectory(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw format("MISSING_DIRECTORY", directory.toString(),
                "canonical map directory does not exist");
        }
    }

    private static Path resolveContainedFile(Path root, String relative)
        throws CanonicalMapFormatException {
        if (relative == null || relative.isEmpty()) {
            throw format("UNSAFE_PATH", "manifest", "referenced path must not be empty");
        }
        Path relativePath;
        try {
            relativePath = Paths.get(relative);
        } catch (InvalidPathException invalid) {
            throw format("UNSAFE_PATH", relative, "referenced path is invalid");
        }
        if (relativePath.isAbsolute() || !relativePath.equals(relativePath.normalize())) {
            throw format("UNSAFE_PATH", relative,
                "referenced path must be a normalized relative path");
        }
        Path candidate = root.resolve(relativePath).normalize();
        Path normalizedRoot = root.normalize();
        if (!candidate.startsWith(normalizedRoot)) {
            throw format("UNSAFE_PATH", relative,
                "referenced file must remain inside the map directory");
        }
        try {
            Path current = normalizedRoot;
            for (Path component : relativePath) {
                current = current.resolve(component);
                if (Files.isSymbolicLink(current)) {
                    throw format("UNSAFE_PATH", relative,
                        "referenced paths must not contain symbolic links");
                }
            }
            Path realRoot = normalizedRoot.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) {
                throw format("UNSAFE_PATH", relative,
                    "referenced file resolves outside the map directory");
            }
        } catch (CanonicalMapFormatException unsafe) {
            throw unsafe;
        } catch (IOException missing) {
            throw format("MISSING_FILE", candidate.toString(),
                "required file is missing or unsafe");
        }
        return candidate;
    }

    private static void requireVersion(
        Map<String, Object> json,
        String name,
        int expected,
        String path
    ) throws CanonicalMapFormatException {
        int actual = integer(json, name, path);
        if (actual != expected) {
            throw format("VERSION_MISMATCH", path + '.' + name,
                "expected " + expected + " but found " + actual);
        }
    }

    private static byte[] decodeBase64(String value, String path)
        throws CanonicalMapFormatException {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException invalid) {
            throw format("INVALID_BASE64", path, "invalid Base64 data");
        }
    }

    private static int integer(Map<String, Object> json, String name, String path)
        throws CanonicalMapFormatException {
        Object value = json.get(name);
        if (!(value instanceof Long)) {
            throw format("INVALID_FIELD_TYPE", path + '.' + name, "expected integer");
        }
        long number = ((Long) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw format("INTEGER_OUT_OF_RANGE", path + '.' + name,
                "integer is outside signed 32-bit range");
        }
        return (int) number;
    }

    private static double number(Map<String, Object> json, String name, String path)
        throws CanonicalMapFormatException {
        Object value = json.get(name);
        if (!(value instanceof Number)) {
            throw format("INVALID_FIELD_TYPE", path + '.' + name, "expected number");
        }
        return ((Number) value).doubleValue();
    }

    private static String string(Map<String, Object> json, String name, String path)
        throws CanonicalMapFormatException {
        Object value = json.get(name);
        if (!(value instanceof String)) {
            throw format("INVALID_FIELD_TYPE", path + '.' + name, "expected string");
        }
        return (String) value;
    }

    private static String nullableString(
        Map<String, Object> json,
        String name,
        String path
    ) throws CanonicalMapFormatException {
        if (!json.containsKey(name)) {
            throw format("MISSING_FIELD", path + '.' + name, "required nullable field is missing");
        }
        Object value = json.get(name);
        if (value != null && !(value instanceof String)) {
            throw format("INVALID_FIELD_TYPE", path + '.' + name,
                "expected string or null");
        }
        return (String) value;
    }

    private static Map<String, Object> object(
        Map<String, Object> json,
        String name,
        String path
    ) throws CanonicalMapFormatException {
        return asObject(json.get(name), path + '.' + name);
    }

    private static Map<String, Object> asObject(Object value, String path)
        throws CanonicalMapFormatException {
        if (!(value instanceof Map)) {
            throw format("INVALID_FIELD_TYPE", path, "expected object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) value;
        return typed;
    }

    private static List<Object> array(Map<String, Object> json, String name, String path)
        throws CanonicalMapFormatException {
        Object value = json.get(name);
        if (!(value instanceof List)) {
            throw format("INVALID_FIELD_TYPE", path + '.' + name, "expected array");
        }
        @SuppressWarnings("unchecked")
        List<Object> typed = (List<Object>) value;
        return typed;
    }

    private static <T extends Enum<T>> T enumValue(
        Class<T> type,
        String name,
        String path
    ) throws CanonicalMapFormatException {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException invalid) {
            throw format("INVALID_ENUM", path, "unsupported value: " + name);
        }
    }

    private static LinkedHashMap<String, Object> object() {
        return new LinkedHashMap<String, Object>();
    }

    private static CanonicalMapFormatException format(
        String code,
        String path,
        String message
    ) {
        return new CanonicalMapFormatException(code, path, message);
    }

    private static final class ManifestData {
        private final CanonicalMapManifest manifest;
        private final String landmarksFile;
        private final List<String> geometryFiles;
        private final String geometrySha256;

        private ManifestData(
            CanonicalMapManifest manifest,
            String landmarksFile,
            List<String> geometryFiles,
            String geometrySha256
        ) {
            this.manifest = manifest;
            this.landmarksFile = landmarksFile;
            this.geometryFiles = Collections.unmodifiableList(
                new ArrayList<String>(geometryFiles)
            );
            this.geometrySha256 = geometrySha256;
        }
    }

    static final class LoadBudget {
        private long totalBytes;

        void accountBytes(long bytes, String path) throws CanonicalMapFormatException {
            if (bytes < 0L
                || totalBytes > CanonicalMapLimits.MAX_TOTAL_MAP_BYTES - bytes) {
                throw format("MAP_TOO_LARGE", path,
                    "aggregate canonical map data exceeds "
                        + CanonicalMapLimits.MAX_TOTAL_MAP_BYTES + " bytes");
            }
            totalBytes += bytes;
        }
    }
}
