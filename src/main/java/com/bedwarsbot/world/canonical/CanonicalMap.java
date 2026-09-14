package com.bedwarsbot.world.canonical;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class CanonicalMap {
    private final CanonicalMapManifest manifest;
    private final List<CanonicalChunk> chunks;
    private final List<CanonicalLandmark> landmarks;

    public CanonicalMap(
        CanonicalMapManifest manifest,
        List<CanonicalChunk> chunks,
        List<CanonicalLandmark> landmarks
    ) {
        this.manifest = manifest;
        List<CanonicalChunk> chunkCopy = chunks == null
            ? null
            : new ArrayList<CanonicalChunk>(chunks);
        if (chunkCopy != null) {
            Collections.sort(chunkCopy, new Comparator<CanonicalChunk>() {
                @Override
                public int compare(CanonicalChunk left, CanonicalChunk right) {
                    if (left == null) return right == null ? 0 : -1;
                    if (right == null) return 1;
                    return left.compareTo(right);
                }
            });
            chunkCopy = Collections.unmodifiableList(chunkCopy);
        }
        this.chunks = chunkCopy;
        List<CanonicalLandmark> landmarkCopy = landmarks == null
            ? null
            : new ArrayList<CanonicalLandmark>(landmarks);
        if (landmarkCopy != null) {
            Collections.sort(landmarkCopy, new Comparator<CanonicalLandmark>() {
                @Override
                public int compare(CanonicalLandmark left, CanonicalLandmark right) {
                    if (left == null) return right == null ? 0 : -1;
                    if (right == null) return 1;
                    return left.compareTo(right);
                }
            });
            landmarkCopy = Collections.unmodifiableList(landmarkCopy);
        }
        this.landmarks = landmarkCopy;
    }

    public CanonicalMapManifest getManifest() { return manifest; }
    public List<CanonicalChunk> getChunks() { return chunks; }
    public List<CanonicalLandmark> getLandmarks() { return landmarks; }

    public Optional<CanonicalChunk> findChunk(int chunkX, int chunkZ) {
        if (chunks == null) return Optional.empty();
        for (CanonicalChunk chunk : chunks) {
            if (chunk == null) continue;
            if (chunk.getChunkX() == chunkX && chunk.getChunkZ() == chunkZ) {
                return Optional.of(chunk);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CanonicalMap)) return false;
        CanonicalMap that = (CanonicalMap) other;
        return equal(manifest, that.manifest)
            && equal(chunks, that.chunks)
            && equal(landmarks, that.landmarks);
    }

    @Override
    public int hashCode() {
        int result = manifest == null ? 0 : manifest.hashCode();
        result = 31 * result + (chunks == null ? 0 : chunks.hashCode());
        result = 31 * result + (landmarks == null ? 0 : landmarks.hashCode());
        return result;
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
