package com.bedwarsbot.observation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SparseBlockOverlay {
    private static final int RECENT_CHANGE_LIMIT = 32;

    public enum Availability {
        UNKNOWN,
        KNOWN,
        STALE
    }

    public enum Outcome {
        ADDED,
        UPDATED,
        DUPLICATE,
        REFRESHED,
        UNAVAILABLE,
        CHUNK_LOADED,
        CHUNK_UNLOADED,
        DIMENSION_UNLOADED,
        OUT_OF_ORDER
    }

    private final Map<BlockPosition, Entry> entries = new HashMap<BlockPosition, Entry>();
    private final Map<ChunkKey, Set<BlockPosition>> positionsByChunk =
        new HashMap<ChunkKey, Set<BlockPosition>>();
    private final Set<ChunkKey> loadedChunks = new HashSet<ChunkKey>();
    private final Map<ChunkKey, Long> chunkLifecycleSequences = new HashMap<ChunkKey, Long>();
    private final Map<Integer, Long> dimensionLifecycleSequences = new HashMap<Integer, Long>();
    private final Deque<BlockPosition> recentChanges = new ArrayDeque<BlockPosition>();

    private int knownCount;
    private int staleCount;
    private long observedChunkLoads;
    private long observedChunkUnloads;
    private long observedBlockEvents;
    private long unavailableBlockEvents;
    private long duplicateBlockEvents;
    private long outOfOrderEvents;

    public synchronized ApplyResult apply(ObservationEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        switch (event.getType()) {
            case BLOCK_STATE:
                observedBlockEvents++;
                return applyBlockState(event);
            case BLOCK_UNAVAILABLE:
                observedBlockEvents++;
                unavailableBlockEvents++;
                return applyUnavailable(event);
            case CHUNK_LOADED:
                observedChunkLoads++;
                return applyChunkLoaded(event);
            case CHUNK_UNLOADED:
                observedChunkUnloads++;
                return applyChunkUnloaded(event);
            case DIMENSION_UNLOADED:
                return applyDimensionUnloaded(event);
            default:
                throw new IllegalStateException("Unhandled observation type " + event.getType());
        }
    }

    public synchronized OverlayValue lookup(BlockPosition position) {
        if (position == null) {
            throw new IllegalArgumentException("position must not be null");
        }
        Entry entry = entries.get(position);
        return entry == null ? OverlayValue.unknown() : entry.toValue();
    }

    public synchronized Snapshot snapshot() {
        List<RecentChange> recent = new ArrayList<RecentChange>(recentChanges.size());
        for (BlockPosition position : recentChanges) {
            Entry entry = entries.get(position);
            if (entry != null) {
                recent.add(new RecentChange(position, entry.toValue()));
            }
        }
        return new Snapshot(
            loadedChunks.size(),
            entries.size(),
            knownCount,
            staleCount,
            observedChunkLoads,
            observedChunkUnloads,
            observedBlockEvents,
            unavailableBlockEvents,
            duplicateBlockEvents,
            outOfOrderEvents,
            recent
        );
    }

    private ApplyResult applyBlockState(ObservationEvent event) {
        BlockPosition position = event.getPosition();
        OverlayValue previous = lookup(position);
        if (isOutOfOrder(event, previous)) {
            return outOfOrder(event, previous);
        }

        Outcome outcome;
        if (previous.getAvailability() == Availability.UNKNOWN) {
            outcome = Outcome.ADDED;
        } else if (previous.getAvailability() == Availability.STALE) {
            outcome = Outcome.REFRESHED;
        } else if (previous.getBlockState().equals(event.getBlockState())) {
            outcome = Outcome.DUPLICATE;
            duplicateBlockEvents++;
        } else {
            outcome = Outcome.UPDATED;
        }

        Entry replacement = new Entry(
            event.getBlockState(),
            Availability.KNOWN,
            event.getSequence(),
            event.getClientTick(),
            event.getWorldTick()
        );
        putEntry(position, replacement);
        if (outcome != Outcome.DUPLICATE) {
            recordRecentChange(position);
        }
        return new ApplyResult(event, outcome, previous, replacement.toValue(), 1);
    }

    private ApplyResult applyUnavailable(ObservationEvent event) {
        BlockPosition position = event.getPosition();
        OverlayValue previous = lookup(position);
        if (isOutOfOrder(event, previous)) {
            return outOfOrder(event, previous);
        }
        if (previous.getAvailability() == Availability.UNKNOWN) {
            return new ApplyResult(event, Outcome.UNAVAILABLE, previous, previous, 0);
        }

        Entry entry = entries.get(position);
        int affected = 0;
        if (entry.availability == Availability.KNOWN) {
            knownCount--;
            staleCount++;
            affected = 1;
        }
        Entry stale = entry.asStale(event.getSequence());
        entries.put(position, stale);
        return new ApplyResult(event, Outcome.UNAVAILABLE, previous, stale.toValue(), affected);
    }

    private ApplyResult applyChunkLoaded(ObservationEvent event) {
        ChunkKey key = new ChunkKey(event.getDimension(), event.getChunkX(), event.getChunkZ());
        if (isLifecycleOutOfOrder(event, key)) {
            return outOfOrder(event, OverlayValue.unknown());
        }
        chunkLifecycleSequences.put(key, event.getSequence());
        loadedChunks.add(key);
        return new ApplyResult(
            event,
            Outcome.CHUNK_LOADED,
            OverlayValue.unknown(),
            OverlayValue.unknown(),
            0
        );
    }

    private ApplyResult applyChunkUnloaded(ObservationEvent event) {
        ChunkKey key = new ChunkKey(event.getDimension(), event.getChunkX(), event.getChunkZ());
        if (isLifecycleOutOfOrder(event, key)) {
            return outOfOrder(event, OverlayValue.unknown());
        }
        chunkLifecycleSequences.put(key, event.getSequence());
        loadedChunks.remove(key);

        int affected = 0;
        Set<BlockPosition> chunkPositions = positionsByChunk.get(key);
        if (chunkPositions != null) {
            for (BlockPosition position : chunkPositions) {
                Entry entry = entries.get(position);
                if (entry != null && entry.sequence < event.getSequence()) {
                    if (entry.availability == Availability.KNOWN) {
                        knownCount--;
                        staleCount++;
                        affected++;
                    }
                    entries.put(position, entry.asStale(event.getSequence()));
                }
            }
        }
        return new ApplyResult(
            event,
            Outcome.CHUNK_UNLOADED,
            OverlayValue.unknown(),
            OverlayValue.unknown(),
            affected
        );
    }

    private ApplyResult applyDimensionUnloaded(ObservationEvent event) {
        Long previousSequence = dimensionLifecycleSequences.get(event.getDimension());
        if (previousSequence != null && event.getSequence() <= previousSequence.longValue()) {
            return outOfOrder(event, OverlayValue.unknown());
        }
        dimensionLifecycleSequences.put(event.getDimension(), event.getSequence());

        Iterator<ChunkKey> loadedIterator = loadedChunks.iterator();
        while (loadedIterator.hasNext()) {
            if (loadedIterator.next().dimension == event.getDimension()) {
                loadedIterator.remove();
            }
        }

        int affected = 0;
        for (Map.Entry<BlockPosition, Entry> overlayEntry : entries.entrySet()) {
            if (overlayEntry.getKey().getDimension() != event.getDimension()) {
                continue;
            }
            Entry entry = overlayEntry.getValue();
            if (entry.sequence < event.getSequence()) {
                if (entry.availability == Availability.KNOWN) {
                    knownCount--;
                    staleCount++;
                    affected++;
                }
                overlayEntry.setValue(entry.asStale(event.getSequence()));
            }
        }
        return new ApplyResult(
            event,
            Outcome.DIMENSION_UNLOADED,
            OverlayValue.unknown(),
            OverlayValue.unknown(),
            affected
        );
    }

    private boolean isOutOfOrder(ObservationEvent event, OverlayValue previous) {
        if (previous.getAvailability() != Availability.UNKNOWN
            && event.getSequence() <= previous.getLastSequence()) {
            return true;
        }
        return isLifecycleOutOfOrder(
            event,
            new ChunkKey(event.getDimension(), event.getChunkX(), event.getChunkZ())
        );
    }

    private boolean isLifecycleOutOfOrder(ObservationEvent event, ChunkKey key) {
        Long dimensionSequence = dimensionLifecycleSequences.get(event.getDimension());
        if (dimensionSequence != null && event.getSequence() <= dimensionSequence.longValue()) {
            return true;
        }
        Long chunkSequence = chunkLifecycleSequences.get(key);
        return chunkSequence != null && event.getSequence() <= chunkSequence.longValue();
    }

    private ApplyResult outOfOrder(ObservationEvent event, OverlayValue current) {
        outOfOrderEvents++;
        return new ApplyResult(event, Outcome.OUT_OF_ORDER, current, current, 0);
    }

    private void putEntry(BlockPosition position, Entry replacement) {
        Entry previous = entries.put(position, replacement);
        if (previous == null) {
            knownCount++;
            ChunkKey key = new ChunkKey(
                position.getDimension(),
                position.getChunkX(),
                position.getChunkZ()
            );
            Set<BlockPosition> positions = positionsByChunk.get(key);
            if (positions == null) {
                positions = new HashSet<BlockPosition>();
                positionsByChunk.put(key, positions);
            }
            positions.add(position);
        } else if (previous.availability == Availability.STALE) {
            staleCount--;
            knownCount++;
        }
    }

    private void recordRecentChange(BlockPosition position) {
        recentChanges.remove(position);
        recentChanges.addFirst(position);
        while (recentChanges.size() > RECENT_CHANGE_LIMIT) {
            recentChanges.removeLast();
        }
    }

    private static final class Entry {
        private final BlockStateSnapshot blockState;
        private final Availability availability;
        private final long sequence;
        private final long clientTick;
        private final Long worldTick;

        private Entry(
            BlockStateSnapshot blockState,
            Availability availability,
            long sequence,
            long clientTick,
            Long worldTick
        ) {
            this.blockState = blockState;
            this.availability = availability;
            this.sequence = sequence;
            this.clientTick = clientTick;
            this.worldTick = worldTick;
        }

        private Entry asStale(long staleSequence) {
            return new Entry(blockState, Availability.STALE, staleSequence, clientTick, worldTick);
        }

        private OverlayValue toValue() {
            return new OverlayValue(blockState, availability, sequence, clientTick, worldTick);
        }
    }

    private static final class ChunkKey {
        private final int dimension;
        private final int chunkX;
        private final int chunkZ;

        private ChunkKey(int dimension, int chunkX, int chunkZ) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ChunkKey)) {
                return false;
            }
            ChunkKey that = (ChunkKey) other;
            return dimension == that.dimension && chunkX == that.chunkX && chunkZ == that.chunkZ;
        }

        @Override
        public int hashCode() {
            int result = dimension;
            result = 31 * result + chunkX;
            result = 31 * result + chunkZ;
            return result;
        }
    }

    public static final class OverlayValue {
        private static final OverlayValue UNKNOWN = new OverlayValue(
            null,
            Availability.UNKNOWN,
            -1L,
            -1L,
            null
        );

        private final BlockStateSnapshot blockState;
        private final Availability availability;
        private final long lastSequence;
        private final long clientTick;
        private final Long worldTick;

        private OverlayValue(
            BlockStateSnapshot blockState,
            Availability availability,
            long lastSequence,
            long clientTick,
            Long worldTick
        ) {
            this.blockState = blockState;
            this.availability = availability;
            this.lastSequence = lastSequence;
            this.clientTick = clientTick;
            this.worldTick = worldTick;
        }

        public static OverlayValue unknown() {
            return UNKNOWN;
        }

        public BlockStateSnapshot getBlockState() {
            return blockState;
        }

        public Availability getAvailability() {
            return availability;
        }

        public long getLastSequence() {
            return lastSequence;
        }

        public long getClientTick() {
            return clientTick;
        }

        public Long getWorldTick() {
            return worldTick;
        }
    }

    public static final class ApplyResult {
        private final ObservationEvent event;
        private final Outcome outcome;
        private final OverlayValue previousValue;
        private final OverlayValue currentValue;
        private final int affectedEntries;

        private ApplyResult(
            ObservationEvent event,
            Outcome outcome,
            OverlayValue previousValue,
            OverlayValue currentValue,
            int affectedEntries
        ) {
            this.event = event;
            this.outcome = outcome;
            this.previousValue = previousValue;
            this.currentValue = currentValue;
            this.affectedEntries = affectedEntries;
        }

        public ObservationEvent getEvent() {
            return event;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        public OverlayValue getPreviousValue() {
            return previousValue;
        }

        public OverlayValue getCurrentValue() {
            return currentValue;
        }

        public int getAffectedEntries() {
            return affectedEntries;
        }
    }

    public static final class RecentChange {
        private final BlockPosition position;
        private final OverlayValue value;

        private RecentChange(BlockPosition position, OverlayValue value) {
            this.position = position;
            this.value = value;
        }

        public BlockPosition getPosition() {
            return position;
        }

        public OverlayValue getValue() {
            return value;
        }
    }

    public static final class Snapshot {
        private final int loadedChunkCount;
        private final int overlaySize;
        private final int knownCount;
        private final int staleCount;
        private final long observedChunkLoads;
        private final long observedChunkUnloads;
        private final long observedBlockEvents;
        private final long unavailableBlockEvents;
        private final long duplicateBlockEvents;
        private final long outOfOrderEvents;
        private final List<RecentChange> recentChanges;

        private Snapshot(
            int loadedChunkCount,
            int overlaySize,
            int knownCount,
            int staleCount,
            long observedChunkLoads,
            long observedChunkUnloads,
            long observedBlockEvents,
            long unavailableBlockEvents,
            long duplicateBlockEvents,
            long outOfOrderEvents,
            List<RecentChange> recentChanges
        ) {
            this.loadedChunkCount = loadedChunkCount;
            this.overlaySize = overlaySize;
            this.knownCount = knownCount;
            this.staleCount = staleCount;
            this.observedChunkLoads = observedChunkLoads;
            this.observedChunkUnloads = observedChunkUnloads;
            this.observedBlockEvents = observedBlockEvents;
            this.unavailableBlockEvents = unavailableBlockEvents;
            this.duplicateBlockEvents = duplicateBlockEvents;
            this.outOfOrderEvents = outOfOrderEvents;
            this.recentChanges = Collections.unmodifiableList(
                new ArrayList<RecentChange>(recentChanges)
            );
        }

        public int getLoadedChunkCount() {
            return loadedChunkCount;
        }

        public int getOverlaySize() {
            return overlaySize;
        }

        public int getKnownCount() {
            return knownCount;
        }

        public int getStaleCount() {
            return staleCount;
        }

        public long getObservedChunkLoads() {
            return observedChunkLoads;
        }

        public long getObservedChunkUnloads() {
            return observedChunkUnloads;
        }

        public long getObservedBlockEvents() {
            return observedBlockEvents;
        }

        public long getUnavailableBlockEvents() {
            return unavailableBlockEvents;
        }

        public long getDuplicateBlockEvents() {
            return duplicateBlockEvents;
        }

        public long getOutOfOrderEvents() {
            return outOfOrderEvents;
        }

        public List<RecentChange> getRecentChanges() {
            return recentChanges;
        }
    }
}
