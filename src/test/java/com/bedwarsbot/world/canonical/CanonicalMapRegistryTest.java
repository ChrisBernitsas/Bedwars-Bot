package com.bedwarsbot.world.canonical;

import java.util.Arrays;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class CanonicalMapRegistryTest {
    @Test
    public void exactRegistryLookupFindsMap() {
        CanonicalMap map = CanonicalMapTestFixtures.validMap();
        CanonicalMapRegistry registry = new CanonicalMapRegistry(Arrays.asList(map));

        CanonicalMapRegistry.LookupResult result = registry.lookup(
            "synthetic-test", "1", "testing-only", Integer.valueOf(1)
        );
        assertEquals(CanonicalMapRegistry.LookupStatus.FOUND, result.getStatus());
        assertSame(map, result.getMap());
    }

    @Test
    public void missingRegistryLookupIsExplicit() {
        CanonicalMapRegistry registry = new CanonicalMapRegistry(
            Arrays.asList(CanonicalMapTestFixtures.validMap())
        );

        assertEquals(CanonicalMapRegistry.LookupStatus.MISSING,
            registry.lookup("missing", null, null, null).getStatus());
    }

    @Test
    public void omittedRevisionCanBeAmbiguous() {
        CanonicalMap first = CanonicalMapTestFixtures.validMap();
        CanonicalMap second = withIdentity(first, "synthetic-test", "2", 1);
        CanonicalMapRegistry registry = new CanonicalMapRegistry(
            Arrays.asList(second, first)
        );

        CanonicalMapRegistry.LookupResult result = registry.lookup(
            "synthetic-test", null, "testing-only", null
        );
        assertEquals(CanonicalMapRegistry.LookupStatus.AMBIGUOUS, result.getStatus());
        assertEquals("1", result.getCandidates().get(0).getManifest().getMapRevision());
        assertEquals("2", result.getCandidates().get(1).getManifest().getMapRevision());
    }

    @Test
    public void unsupportedSchemaLookupIsExplicit() {
        CanonicalMap map = withIdentity(
            CanonicalMapTestFixtures.validMap(), "synthetic-test", "future", 2
        );
        CanonicalMapRegistry registry = new CanonicalMapRegistry(Arrays.asList(map));

        assertEquals(CanonicalMapRegistry.LookupStatus.UNSUPPORTED_SCHEMA,
            registry.lookup("synthetic-test", "future", null, null).getStatus());
        assertEquals(CanonicalMapRegistry.LookupStatus.UNSUPPORTED_SCHEMA,
            registry.lookup("synthetic-test", null, null, Integer.valueOf(2)).getStatus());
    }

    @Test
    public void duplicateRegistryEntriesAreRejected() {
        CanonicalMap map = CanonicalMapTestFixtures.validMap();
        try {
            new CanonicalMapRegistry(Arrays.asList(map, map));
        } catch (CanonicalMapRegistryException expected) {
            assertEquals("DUPLICATE_REGISTRY_ENTRY", expected.getCode());
            return;
        }
        throw new AssertionError("expected duplicate registry rejection");
    }

    @Test
    public void nullRegistryEntryIsRejectedStructurally() {
        try {
            new CanonicalMapRegistry(Arrays.asList((CanonicalMap) null));
        } catch (CanonicalMapRegistryException expected) {
            assertEquals("INVALID_REGISTRY_ENTRY", expected.getCode());
            return;
        }
        throw new AssertionError("expected invalid registry entry rejection");
    }

    private static CanonicalMap withIdentity(
        CanonicalMap source,
        String id,
        String revision,
        int schema
    ) {
        CanonicalMapManifest original = source.getManifest();
        CanonicalMapManifest manifest = new CanonicalMapManifest(
            schema,
            id,
            original.getDisplayName(),
            revision,
            original.getBedwarsMode(),
            original.getMinecraftVersion(),
            original.getDimension(),
            original.getBounds(),
            original.getSourceProvenance(),
            original.getAcquisitionDate(),
            original.getSourceReference(),
            original.getSourceChecksum(),
            original.getValidationStatus(),
            original.getNotes(),
            original.getGeometryFormat(),
            original.getGeometryFormatVersion(),
            original.getLandmarkFormat(),
            original.getLandmarkFormatVersion()
        );
        return CanonicalMapTestFixtures.withManifest(source, manifest);
    }
}
