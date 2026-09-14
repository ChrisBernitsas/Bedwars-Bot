package com.bedwarsbot.observation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ClientObservedChunkStoreTest {
    private static final ChunkSnapshotKey KEY = new ChunkSnapshotKey(0, 1, 2);

    @Test
    public void capturedAirIsKnownWhileUncapturedSectionRemainsUnknown() {
        ClientObservedChunkStore store = new ClientObservedChunkStore();
        store.apply(scheduled(1L, 1L));
        store.apply(section(2L, 1L, ChunkSectionSnapshot.uniform(0, 0), 1));

        ClientObservedChunkStore.ObservedBlockValue air =
            store.lookup(position(3, 4, 5));
        ClientObservedChunkStore.ObservedBlockValue unknown =
            store.lookup(position(3, 20, 5));

        assertEquals(ClientObservedChunkStore.Availability.KNOWN, air.getAvailability());
        assertTrue(air.isKnownAir());
        assertEquals(ClientObservedChunkStore.Availability.UNKNOWN,
            unknown.getAvailability());
        assertFalse(unknown.isKnownAir());
    }

    @Test
    public void fullAndPartialCompletionAreExplicit() {
        ClientObservedChunkStore complete = new ClientObservedChunkStore();
        complete.apply(scheduled(1L, 1L));
        for (int section = 0; section < 16; section++) {
            complete.apply(section(
                2L + section,
                1L,
                ChunkSectionSnapshot.uniform(section, 0),
                section + 1
            ));
        }
        ClientObservedChunkStore.ApplyResult completion = complete.apply(
            ChunkSnapshotEvent.completed(18L, 1L, 2L, 3L, KEY, 1L)
        );

        assertEquals(ClientObservedChunkStore.ApplyOutcome.COMPLETED,
            completion.getOutcome());
        assertEquals(1, complete.snapshot().getCompleteChunks());
        assertEquals(16, complete.snapshot().getCoveredSections());

        ClientObservedChunkStore partial = new ClientObservedChunkStore();
        partial.apply(scheduled(1L, 1L));
        partial.apply(section(2L, 1L, ChunkSectionSnapshot.uniform(0, 0), 1));
        partial.apply(ChunkSnapshotEvent.aborted(
            3L, 1L, 2L, 3L, KEY, 1L, 1, "test_abort"
        ));

        assertEquals(1, partial.snapshot().getPartialChunks());
        assertEquals(1L, partial.snapshot().getAbortedSnapshots());
        assertEquals(ClientObservedChunkStore.Availability.KNOWN,
            partial.lookup(position(0, 0, 0)).getAvailability());
        assertEquals(ClientObservedChunkStore.Availability.UNKNOWN,
            partial.lookup(position(0, 16, 0)).getAvailability());
    }

    @Test
    public void completionWithMissingSectionsRemainsPartial() {
        ClientObservedChunkStore store = new ClientObservedChunkStore();
        store.apply(scheduled(1L, 1L));
        store.apply(section(2L, 1L, ChunkSectionSnapshot.uniform(0, 0), 1));

        ClientObservedChunkStore.ApplyResult result = store.apply(
            ChunkSnapshotEvent.completed(3L, 1L, 2L, 3L, KEY, 1L)
        );

        assertEquals(ClientObservedChunkStore.ApplyOutcome.PARTIAL, result.getOutcome());
        assertEquals(0, store.snapshot().getCompleteChunks());
        assertEquals(1, store.snapshot().getPartialChunks());
    }

    @Test
    public void unloadMakesCapturedCoverageStale() {
        ClientObservedChunkStore store = new ClientObservedChunkStore();
        store.apply(scheduled(1L, 1L));
        store.apply(section(2L, 1L, ChunkSectionSnapshot.uniform(0, 0), 1));
        store.apply(ChunkSnapshotEvent.unloaded(3L, 1L, 2L, 3L, KEY, 1L, 1));

        assertEquals(ClientObservedChunkStore.Availability.STALE,
            store.lookup(position(0, 0, 0)).getAvailability());
        assertEquals(1, store.snapshot().getStaleChunks());
    }

    @Test
    public void reloadGenerationRejectsLateOldSection() {
        ClientObservedChunkStore store = new ClientObservedChunkStore();
        store.apply(scheduled(1L, 1L));
        store.apply(section(2L, 1L, ChunkSectionSnapshot.uniform(0, 0), 1));
        store.apply(ChunkSnapshotEvent.unloaded(3L, 1L, 2L, 3L, KEY, 1L, 1));
        store.apply(scheduled(4L, 2L));

        ClientObservedChunkStore.ApplyResult late = store.apply(
            section(5L, 1L, ChunkSectionSnapshot.uniform(1, 7), 2)
        );

        assertEquals(ClientObservedChunkStore.ApplyOutcome.OLD_GENERATION,
            late.getOutcome());
        assertEquals(ClientObservedChunkStore.Availability.UNKNOWN,
            store.lookup(position(0, 0, 0)).getAvailability());
        assertEquals(1L, store.snapshot().getRejectedOldGenerations());
    }

    @Test
    public void newestClientCaptureWinsBetweenSectionAndBlockObservation() {
        ClientObservedChunkStore store = new ClientObservedChunkStore();
        store.apply(scheduled(1L, 1L));
        BlockPosition changed = position(2, 3, 4);
        store.apply(ChunkSnapshotEvent.blockStateObserved(
            5L, 1L, 2L, 3L, KEY, 1L, changed, 35
        ));

        store.apply(section(4L, 1L, ChunkSectionSnapshot.uniform(0, 0), 1));
        assertEquals(35, store.lookup(changed).getStateId());
        assertEquals(5L, store.lookup(changed).getCaptureSequence());

        store.apply(section(6L, 1L, ChunkSectionSnapshot.uniform(0, 0), 1));
        assertTrue(store.lookup(changed).isKnownAir());
        ClientObservedChunkStore.ApplyResult oldBlock = store.apply(
            ChunkSnapshotEvent.blockStateObserved(
                5L, 1L, 2L, 3L, KEY, 1L, changed, 1
            )
        );
        assertEquals(ClientObservedChunkStore.ApplyOutcome.OUT_OF_ORDER,
            oldBlock.getOutcome());
        assertTrue(store.lookup(changed).isKnownAir());
    }

    @Test
    public void sectionSnapshotDefensivelyCopiesMutableSourceArray() {
        char[] mutable = new char[4096];
        mutable[0] = 7;
        ChunkSectionSnapshot snapshot = ChunkSectionSnapshot.copyDense(0, mutable);
        mutable[0] = 99;

        assertEquals(7, snapshot.getStateId(0));
    }

    private static ChunkSnapshotEvent scheduled(long sequence, long generation) {
        return ChunkSnapshotEvent.scheduled(
            sequence, 1L, 2L, 3L, KEY, generation, "SCHEDULED"
        );
    }

    private static ChunkSnapshotEvent section(
        long sequence,
        long generation,
        ChunkSectionSnapshot snapshot,
        int capturedSections
    ) {
        return ChunkSnapshotEvent.sectionCaptured(
            sequence, 1L, 2L, 3L, KEY, generation, snapshot, capturedSections
        );
    }

    private static BlockPosition position(int localX, int y, int localZ) {
        return new BlockPosition(
            KEY.getDimension(),
            KEY.getChunkX() * 16 + localX,
            y,
            KEY.getChunkZ() * 16 + localZ
        );
    }
}
