package com.bedwarsbot.observation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class SparseBlockOverlayTest {
    private static final BlockStateSnapshot STONE =
        new BlockStateSnapshot(1, "minecraft:stone", 0);
    private static final BlockStateSnapshot WOOL =
        new BlockStateSnapshot(35, "minecraft:wool", 14);

    @Test
    public void positionIsUnknownUntilSpecificallyObservedAndDimensionsAreIndependent() {
        SparseBlockOverlay overlay = new SparseBlockOverlay();
        BlockPosition overworld = new BlockPosition(0, 2, 64, 3);
        BlockPosition nether = new BlockPosition(-1, 2, 64, 3);

        assertEquals(SparseBlockOverlay.Availability.UNKNOWN, overlay.lookup(overworld).getAvailability());
        assertNull(overlay.lookup(overworld).getBlockState());

        assertEquals(SparseBlockOverlay.Outcome.ADDED, overlay.apply(block(1L, overworld, STONE)).getOutcome());
        assertEquals(SparseBlockOverlay.Outcome.ADDED, overlay.apply(block(2L, nether, WOOL)).getOutcome());

        assertEquals(STONE, overlay.lookup(overworld).getBlockState());
        assertEquals(WOOL, overlay.lookup(nether).getBlockState());
        assertEquals(2, overlay.snapshot().getOverlaySize());
        assertEquals(2, overlay.snapshot().getKnownCount());
    }

    @Test
    public void ignoresOutOfOrderEventsAndRetainsNewestState() {
        SparseBlockOverlay overlay = new SparseBlockOverlay();
        BlockPosition position = new BlockPosition(0, 4, 65, 5);

        overlay.apply(block(10L, position, STONE));
        SparseBlockOverlay.ApplyResult older = overlay.apply(block(9L, position, WOOL));

        assertEquals(SparseBlockOverlay.Outcome.OUT_OF_ORDER, older.getOutcome());
        assertEquals(STONE, overlay.lookup(position).getBlockState());
        assertEquals(10L, overlay.lookup(position).getLastSequence());
        assertEquals(1L, overlay.snapshot().getOutOfOrderEvents());
    }

    @Test
    public void duplicateUpdateDoesNotGrowOverlayOrCreateAnotherRecentChange() {
        SparseBlockOverlay overlay = new SparseBlockOverlay();
        BlockPosition position = new BlockPosition(0, 7, 70, 8);

        overlay.apply(block(1L, position, STONE));
        SparseBlockOverlay.ApplyResult duplicate = overlay.apply(block(2L, position, STONE));

        assertEquals(SparseBlockOverlay.Outcome.DUPLICATE, duplicate.getOutcome());
        assertEquals(1, overlay.snapshot().getOverlaySize());
        assertEquals(1, overlay.snapshot().getRecentChanges().size());
        assertEquals(1L, overlay.snapshot().getDuplicateBlockEvents());
        assertEquals(2L, overlay.lookup(position).getLastSequence());
    }

    @Test
    public void unloadMakesEntriesStaleAndReloadDoesNotRefreshThem() {
        SparseBlockOverlay overlay = new SparseBlockOverlay();
        BlockPosition position = new BlockPosition(0, 18, 64, 35);

        overlay.apply(chunkLoaded(1L, position));
        overlay.apply(block(2L, position, STONE));
        overlay.apply(chunkUnloaded(3L, position));

        assertEquals(SparseBlockOverlay.Availability.STALE, overlay.lookup(position).getAvailability());
        assertEquals(STONE, overlay.lookup(position).getBlockState());
        assertEquals(0, overlay.snapshot().getKnownCount());
        assertEquals(1, overlay.snapshot().getStaleCount());

        overlay.apply(chunkLoaded(4L, position));

        assertEquals(SparseBlockOverlay.Availability.STALE, overlay.lookup(position).getAvailability());
        assertEquals(1, overlay.snapshot().getLoadedChunkCount());
    }

    @Test
    public void newExplicitObservationIsRequiredToRefreshAfterReload() {
        SparseBlockOverlay overlay = new SparseBlockOverlay();
        BlockPosition position = new BlockPosition(0, -17, 80, -33);

        overlay.apply(chunkLoaded(1L, position));
        overlay.apply(block(2L, position, WOOL));
        overlay.apply(chunkUnloaded(3L, position));
        overlay.apply(chunkLoaded(4L, position));
        SparseBlockOverlay.ApplyResult refreshed = overlay.apply(block(5L, position, WOOL));

        assertEquals(SparseBlockOverlay.Outcome.REFRESHED, refreshed.getOutcome());
        assertEquals(SparseBlockOverlay.Availability.KNOWN, overlay.lookup(position).getAvailability());
        assertEquals(1, overlay.snapshot().getKnownCount());
        assertEquals(0, overlay.snapshot().getStaleCount());
    }

    @Test
    public void dimensionUnloadStalesOnlyThatDimension() {
        SparseBlockOverlay overlay = new SparseBlockOverlay();
        BlockPosition overworld = new BlockPosition(0, 1, 2, 3);
        BlockPosition nether = new BlockPosition(-1, 1, 2, 3);
        overlay.apply(block(1L, overworld, STONE));
        overlay.apply(block(2L, nether, WOOL));

        overlay.apply(ObservationEvent.dimensionUnloaded(3L, 4L, 5L, 6L, 0));

        assertEquals(SparseBlockOverlay.Availability.STALE, overlay.lookup(overworld).getAvailability());
        assertEquals(SparseBlockOverlay.Availability.KNOWN, overlay.lookup(nether).getAvailability());
        assertEquals(1, overlay.snapshot().getKnownCount());
        assertEquals(1, overlay.snapshot().getStaleCount());
    }

    @Test
    public void unavailablePositionRemainsUnknownOrMakesLastKnownValueStale() {
        SparseBlockOverlay overlay = new SparseBlockOverlay();
        BlockPosition neverObserved = new BlockPosition(0, 10, 64, 10);
        BlockPosition observed = new BlockPosition(0, 11, 64, 11);

        overlay.apply(ObservationEvent.blockUnavailable(1L, 2L, 3L, 4L, neverObserved));
        assertEquals(
            SparseBlockOverlay.Availability.UNKNOWN,
            overlay.lookup(neverObserved).getAvailability()
        );

        overlay.apply(block(2L, observed, STONE));
        overlay.apply(ObservationEvent.blockUnavailable(3L, 4L, 5L, 6L, observed));

        assertEquals(SparseBlockOverlay.Availability.STALE, overlay.lookup(observed).getAvailability());
        assertEquals(STONE, overlay.lookup(observed).getBlockState());
        assertEquals(0, overlay.snapshot().getKnownCount());
        assertEquals(1, overlay.snapshot().getStaleCount());
    }

    private static ObservationEvent block(
        long sequence,
        BlockPosition position,
        BlockStateSnapshot state
    ) {
        return ObservationEvent.blockState(sequence, 20L, 30L, 40L, position, state);
    }

    private static ObservationEvent chunkLoaded(long sequence, BlockPosition position) {
        return ObservationEvent.chunkLoaded(
            sequence,
            20L,
            30L,
            40L,
            position.getDimension(),
            position.getChunkX(),
            position.getChunkZ()
        );
    }

    private static ObservationEvent chunkUnloaded(long sequence, BlockPosition position) {
        return ObservationEvent.chunkUnloaded(
            sequence,
            20L,
            30L,
            40L,
            position.getDimension(),
            position.getChunkX(),
            position.getChunkZ()
        );
    }
}
