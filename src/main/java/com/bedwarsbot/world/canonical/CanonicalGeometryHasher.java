package com.bedwarsbot.world.canonical;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class CanonicalGeometryHasher {
    private static final byte[] DOMAIN =
        "bedwarsbot-canonical-geometry-v1\n".getBytes(StandardCharsets.US_ASCII);

    public String sha256(CanonicalMap map) {
        ValidationReport report = new CanonicalMapValidator().validate(map);
        if (!report.isValid()) {
            throw new IllegalArgumentException(
                "cannot hash invalid canonical geometry: " + report.getErrors().get(0)
            );
        }
        MessageDigest digest = newDigest();
        digest.update(DOMAIN);
        CanonicalMapManifest manifest = map.getManifest();
        updateInt(digest, manifest.getDimension());
        MapBounds bounds = manifest.getBounds();
        updateInt(digest, bounds.getMinX());
        updateInt(digest, bounds.getMinY());
        updateInt(digest, bounds.getMinZ());
        updateInt(digest, bounds.getMaxX());
        updateInt(digest, bounds.getMaxY());
        updateInt(digest, bounds.getMaxZ());
        for (CanonicalChunk chunk : map.getChunks()) {
            for (CanonicalSection section : chunk.getSections()) {
                if (section.getKnownPositionCount() == 0) continue;
                for (int positionIndex = 0;
                    positionIndex < CanonicalSection.POSITION_COUNT;
                    positionIndex++) {
                    if (!section.isKnown(positionIndex)) continue;
                    CanonicalBlockState state = section.getState(positionIndex).get();
                    updateInt(digest, chunk.getChunkX());
                    updateInt(digest, chunk.getChunkZ());
                    updateInt(digest, section.getSectionIndex());
                    updateInt(digest, positionIndex);
                    updateInt(digest, state.getBlockId());
                    updateInt(digest, state.getMetadata());
                }
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format("%02x", value & 255));
        }
        return hex.toString();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256", impossible);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
