package com.bedwarsbot.world.canonical;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CanonicalMapRegistry {
    public enum LookupStatus {
        FOUND,
        MISSING,
        AMBIGUOUS,
        UNSUPPORTED_SCHEMA
    }

    private final List<CanonicalMap> maps;

    public CanonicalMapRegistry(Collection<CanonicalMap> maps) {
        if (maps == null) {
            throw new CanonicalMapRegistryException(
                "REGISTRY_REQUIRED",
                "map collection must not be null"
            );
        }
        List<CanonicalMap> copy = new ArrayList<CanonicalMap>(maps);
        for (CanonicalMap map : copy) {
            if (map == null || map.getManifest() == null) {
                throw new CanonicalMapRegistryException(
                    "INVALID_REGISTRY_ENTRY",
                    "registry entries require a manifest"
                );
            }
        }
        Collections.sort(copy, MAP_COMPARATOR);
        Set<String> keys = new HashSet<String>();
        for (CanonicalMap map : copy) {
            CanonicalMapManifest manifest = map.getManifest();
            String key = manifest.getMapId() + '\u0000'
                + manifest.getMapRevision() + '\u0000'
                + manifest.getBedwarsMode() + '\u0000'
                + manifest.getSchemaVersion();
            if (!keys.add(key)) {
                throw new CanonicalMapRegistryException(
                    "DUPLICATE_REGISTRY_ENTRY",
                    "duplicate map ID/revision/mode/schema: " + manifest.getMapId()
                        + '/' + manifest.getMapRevision()
                        + '/' + manifest.getBedwarsMode()
                        + '/' + manifest.getSchemaVersion()
                );
            }
        }
        this.maps = Collections.unmodifiableList(copy);
    }

    public List<CanonicalMap> getMaps() {
        return maps;
    }

    public LookupResult lookup(
        String mapId,
        String revision,
        String bedwarsMode,
        Integer schemaVersion
    ) {
        if (schemaVersion != null
            && schemaVersion.intValue() != CanonicalMapManifest.SUPPORTED_SCHEMA_VERSION) {
            return new LookupResult(LookupStatus.UNSUPPORTED_SCHEMA, null,
                Collections.<CanonicalMap>emptyList());
        }
        List<CanonicalMap> matchingIdentity = new ArrayList<CanonicalMap>();
        List<CanonicalMap> supported = new ArrayList<CanonicalMap>();
        for (CanonicalMap map : maps) {
            CanonicalMapManifest manifest = map.getManifest();
            if (!equal(mapId, manifest.getMapId())
                || revision != null && !equal(revision, manifest.getMapRevision())
                || bedwarsMode != null && !equal(bedwarsMode, manifest.getBedwarsMode())) {
                continue;
            }
            matchingIdentity.add(map);
            if (manifest.getSchemaVersion()
                == CanonicalMapManifest.SUPPORTED_SCHEMA_VERSION) {
                supported.add(map);
            }
        }
        if (supported.isEmpty()) {
            LookupStatus status = matchingIdentity.isEmpty()
                ? LookupStatus.MISSING
                : LookupStatus.UNSUPPORTED_SCHEMA;
            return new LookupResult(status, null, matchingIdentity);
        }
        if (supported.size() > 1) {
            return new LookupResult(LookupStatus.AMBIGUOUS, null, supported);
        }
        return new LookupResult(LookupStatus.FOUND, supported.get(0), supported);
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static final Comparator<CanonicalMap> MAP_COMPARATOR =
        new Comparator<CanonicalMap>() {
            @Override
            public int compare(CanonicalMap left, CanonicalMap right) {
                CanonicalMapManifest a = left.getManifest();
                CanonicalMapManifest b = right.getManifest();
                int result = compareNullable(a.getMapId(), b.getMapId());
                if (result != 0) return result;
                result = compareNullable(a.getMapRevision(), b.getMapRevision());
                if (result != 0) return result;
                result = compareNullable(a.getBedwarsMode(), b.getBedwarsMode());
                if (result != 0) return result;
                return Integer.compare(a.getSchemaVersion(), b.getSchemaVersion());
            }
        };

    private static int compareNullable(String left, String right) {
        if (left == null) return right == null ? 0 : -1;
        return right == null ? 1 : left.compareTo(right);
    }

    public static final class LookupResult {
        private final LookupStatus status;
        private final CanonicalMap map;
        private final List<CanonicalMap> candidates;

        private LookupResult(
            LookupStatus status,
            CanonicalMap map,
            List<CanonicalMap> candidates
        ) {
            this.status = status;
            this.map = map;
            this.candidates = Collections.unmodifiableList(
                new ArrayList<CanonicalMap>(candidates)
            );
        }

        public LookupStatus getStatus() { return status; }
        public CanonicalMap getMap() { return map; }
        public List<CanonicalMap> getCandidates() { return candidates; }
    }
}
