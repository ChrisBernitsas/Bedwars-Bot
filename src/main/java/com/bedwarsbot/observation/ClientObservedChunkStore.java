package com.bedwarsbot.observation;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class ClientObservedChunkStore {
    public enum ChunkStatus {
        CAPTURING,
        COMPLETE,
        PARTIAL,
        STALE
    }

    public enum Availability {
        UNKNOWN,
        KNOWN,
        STALE
    }

    public enum ApplyOutcome {
        SCHEDULED,
        DUPLICATE,
        SECTION_APPLIED,
        COMPLETED,
        PARTIAL,
        ABORTED,
        STALE,
        BLOCK_APPLIED,
        METRICS,
        OLD_GENERATION,
        OUT_OF_ORDER
    }

    private final Map<ChunkSnapshotKey, ChunkRecord> chunks =
        new HashMap<ChunkSnapshotKey, ChunkRecord>();

    private long completedSnapshots;
    private long abortedSnapshots;
    private long partialSnapshots;
    private long rejectedOldGenerations;
    private long rejectedOutOfOrder;
    private long appliedSections;
    private long appliedBlockUpdates;

    public synchronized ApplyResult apply(ChunkSnapshotEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        switch (event.getType()) {
            case SCHEDULED:
                return applyScheduled(event);
            case SECTION_CAPTURED:
                return applySection(event);
            case COMPLETED:
                return applyCompleted(event);
            case ABORTED:
                return applyAborted(event);
            case UNLOADED:
                return applyUnloaded(event);
            case DIMENSION_UNLOADED:
                return applyDimensionUnloaded(event);
            case BLOCK_STATE_OBSERVED:
                return applyBlockState(event);
            case SCAN_TICK:
                return applyScanMetrics(event);
            default:
                throw new IllegalStateException("Unhandled snapshot event " + event.getType());
        }
    }

    private ApplyResult applyScanMetrics(ChunkSnapshotEvent event) {
        ChunkRecord chunk = chunks.get(event.getChunkKey());
        return new ApplyResult(
            event,
            ApplyOutcome.METRICS,
            chunk == null ? null : chunk.status,
            chunk == null ? 0 : chunk.coveredSections
        );
    }

    public synchronized ObservedBlockValue lookup(BlockPosition position) {
        if (position == null) {
            throw new IllegalArgumentException("position must not be null");
        }
        if (position.getY() < 0 || position.getY() >= 256) {
            return ObservedBlockValue.unknown();
        }
        ChunkSnapshotKey key = new ChunkSnapshotKey(
            position.getDimension(),
            position.getChunkX(),
            position.getChunkZ()
        );
        ChunkRecord chunk = chunks.get(key);
        if (chunk == null) {
            return ObservedBlockValue.unknown();
        }

        int sectionIndex = position.getY() >> 4;
        int localIndex = localIndex(position);
        int absoluteIndex = sectionIndex * ChunkSectionSnapshot.BLOCKS_PER_SECTION + localIndex;
        BlockOverride override = chunk.blockOverrides.get(Integer.valueOf(absoluteIndex));
        SectionRecord section = chunk.sections[sectionIndex];
        if (override == null && section == null) {
            return ObservedBlockValue.unknown();
        }

        int stateId;
        long sequence;
        if (override != null && (section == null || override.sequence > section.captureSequence)) {
            stateId = override.stateId;
            sequence = override.sequence;
        } else {
            stateId = section.snapshot.getStateId(localIndex);
            sequence = section.captureSequence;
        }
        Availability availability = chunk.status == ChunkStatus.STALE
            ? Availability.STALE
            : Availability.KNOWN;
        return new ObservedBlockValue(availability, stateId, sequence, chunk.generation);
    }

    public synchronized StoreSnapshot snapshot() {
        int capturing = 0;
        int complete = 0;
        int partial = 0;
        int stale = 0;
        int coveredSections = 0;
        for (ChunkRecord chunk : chunks.values()) {
            coveredSections += chunk.coveredSections;
            switch (chunk.status) {
                case CAPTURING:
                    capturing++;
                    break;
                case COMPLETE:
                    complete++;
                    break;
                case PARTIAL:
                    partial++;
                    break;
                case STALE:
                    stale++;
                    break;
                default:
                    throw new IllegalStateException("Unhandled chunk status " + chunk.status);
            }
        }
        return new StoreSnapshot(
            chunks.size(),
            capturing,
            complete,
            partial,
            stale,
            coveredSections,
            completedSnapshots,
            abortedSnapshots,
            partialSnapshots,
            rejectedOldGenerations,
            rejectedOutOfOrder,
            appliedSections,
            appliedBlockUpdates
        );
    }

    private ApplyResult applyScheduled(ChunkSnapshotEvent event) {
        ChunkRecord current = chunks.get(event.getChunkKey());
        if (current != null && event.getLoadGeneration() < current.generation) {
            return rejectOldGeneration(event, current.status);
        }
        if (current != null && event.getLoadGeneration() == current.generation) {
            return new ApplyResult(event, ApplyOutcome.DUPLICATE, current.status, current.coveredSections);
        }
        ChunkStatus status = "PENDING_CAPACITY_EXCEEDED".equals(event.getOutcome())
            ? ChunkStatus.PARTIAL
            : ChunkStatus.CAPTURING;
        chunks.put(event.getChunkKey(), new ChunkRecord(event.getLoadGeneration(), status));
        if (status == ChunkStatus.PARTIAL) {
            partialSnapshots++;
        }
        return new ApplyResult(event, ApplyOutcome.SCHEDULED, status, 0);
    }

    private ApplyResult applySection(ChunkSnapshotEvent event) {
        ChunkRecord chunk = currentChunk(event);
        if (chunk == null) {
            return rejectOldGeneration(event, null);
        }
        int sectionIndex = event.getSectionSnapshot().getSectionIndex();
        SectionRecord previous = chunk.sections[sectionIndex];
        if (previous != null && event.getCaptureSequence() <= previous.captureSequence) {
            return rejectOutOfOrder(event, chunk.status, chunk.coveredSections);
        }

        chunk.sections[sectionIndex] = new SectionRecord(
            event.getSectionSnapshot(),
            event.getCaptureSequence()
        );
        if (previous == null) {
            chunk.coveredSections++;
        }
        int minimumIndex = sectionIndex * ChunkSectionSnapshot.BLOCKS_PER_SECTION;
        int maximumIndex = minimumIndex + ChunkSectionSnapshot.BLOCKS_PER_SECTION;
        Iterator<Map.Entry<Integer, BlockOverride>> iterator =
            chunk.blockOverrides.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, BlockOverride> override = iterator.next();
            int index = override.getKey().intValue();
            if (index >= minimumIndex && index < maximumIndex
                && override.getValue().sequence <= event.getCaptureSequence()) {
                iterator.remove();
            }
        }
        appliedSections++;
        return new ApplyResult(
            event,
            ApplyOutcome.SECTION_APPLIED,
            chunk.status,
            chunk.coveredSections
        );
    }

    private ApplyResult applyCompleted(ChunkSnapshotEvent event) {
        ChunkRecord chunk = currentChunk(event);
        if (chunk == null) {
            return rejectOldGeneration(event, null);
        }
        if (chunk.coveredSections == ChunkSectionSnapshot.SECTIONS_PER_CHUNK) {
            if (chunk.status != ChunkStatus.COMPLETE) {
                completedSnapshots++;
            }
            chunk.status = ChunkStatus.COMPLETE;
            return new ApplyResult(
                event,
                ApplyOutcome.COMPLETED,
                chunk.status,
                chunk.coveredSections
            );
        }
        markPartial(chunk);
        return new ApplyResult(event, ApplyOutcome.PARTIAL, chunk.status, chunk.coveredSections);
    }

    private ApplyResult applyAborted(ChunkSnapshotEvent event) {
        ChunkRecord chunk = currentChunk(event);
        if (chunk == null) {
            return rejectOldGeneration(event, null);
        }
        if (!chunk.abortRecorded) {
            abortedSnapshots++;
            chunk.abortRecorded = true;
        }
        markPartial(chunk);
        return new ApplyResult(event, ApplyOutcome.ABORTED, chunk.status, chunk.coveredSections);
    }

    private ApplyResult applyUnloaded(ChunkSnapshotEvent event) {
        ChunkRecord chunk = currentChunk(event);
        if (chunk == null) {
            return rejectOldGeneration(event, null);
        }
        chunk.status = ChunkStatus.STALE;
        return new ApplyResult(event, ApplyOutcome.STALE, chunk.status, chunk.coveredSections);
    }

    private ApplyResult applyDimensionUnloaded(ChunkSnapshotEvent event) {
        int affected = 0;
        for (Map.Entry<ChunkSnapshotKey, ChunkRecord> entry : chunks.entrySet()) {
            if (entry.getKey().getDimension() == event.getChunkKey().getDimension()) {
                entry.getValue().status = ChunkStatus.STALE;
                affected++;
            }
        }
        return new ApplyResult(event, ApplyOutcome.STALE, ChunkStatus.STALE, affected);
    }

    private ApplyResult applyBlockState(ChunkSnapshotEvent event) {
        ChunkRecord chunk = currentChunk(event);
        if (chunk == null || chunk.status == ChunkStatus.STALE) {
            return rejectOldGeneration(event, chunk == null ? null : chunk.status);
        }
        BlockPosition position = event.getBlockPosition();
        if (position.getY() < 0 || position.getY() >= 256) {
            return rejectOutOfOrder(event, chunk.status, chunk.coveredSections);
        }
        int sectionIndex = position.getY() >> 4;
        int localIndex = localIndex(position);
        int absoluteIndex = sectionIndex * ChunkSectionSnapshot.BLOCKS_PER_SECTION + localIndex;
        SectionRecord section = chunk.sections[sectionIndex];
        BlockOverride previous = chunk.blockOverrides.get(Integer.valueOf(absoluteIndex));
        long newestSequence = section == null ? -1L : section.captureSequence;
        if (previous != null && previous.sequence > newestSequence) {
            newestSequence = previous.sequence;
        }
        if (event.getCaptureSequence() <= newestSequence) {
            return rejectOutOfOrder(event, chunk.status, chunk.coveredSections);
        }
        chunk.blockOverrides.put(
            Integer.valueOf(absoluteIndex),
            new BlockOverride(event.getBlockStateId(), event.getCaptureSequence())
        );
        appliedBlockUpdates++;
        return new ApplyResult(event, ApplyOutcome.BLOCK_APPLIED, chunk.status, chunk.coveredSections);
    }

    private ChunkRecord currentChunk(ChunkSnapshotEvent event) {
        ChunkRecord chunk = chunks.get(event.getChunkKey());
        return chunk != null && chunk.generation == event.getLoadGeneration() ? chunk : null;
    }

    private ApplyResult rejectOldGeneration(ChunkSnapshotEvent event, ChunkStatus status) {
        rejectedOldGenerations++;
        return new ApplyResult(event, ApplyOutcome.OLD_GENERATION, status, 0);
    }

    private ApplyResult rejectOutOfOrder(
        ChunkSnapshotEvent event,
        ChunkStatus status,
        int coveredSections
    ) {
        rejectedOutOfOrder++;
        return new ApplyResult(event, ApplyOutcome.OUT_OF_ORDER, status, coveredSections);
    }

    private void markPartial(ChunkRecord chunk) {
        if (chunk.status != ChunkStatus.PARTIAL) {
            partialSnapshots++;
        }
        chunk.status = ChunkStatus.PARTIAL;
    }

    private static int localIndex(BlockPosition position) {
        return (position.getY() & 15) << 8
            | (position.getZ() & 15) << 4
            | (position.getX() & 15);
    }

    private static final class ChunkRecord {
        private final long generation;
        private final SectionRecord[] sections =
            new SectionRecord[ChunkSectionSnapshot.SECTIONS_PER_CHUNK];
        private final Map<Integer, BlockOverride> blockOverrides =
            new HashMap<Integer, BlockOverride>();
        private ChunkStatus status;
        private int coveredSections;
        private boolean abortRecorded;

        private ChunkRecord(long generation, ChunkStatus status) {
            this.generation = generation;
            this.status = status;
        }
    }

    private static final class SectionRecord {
        private final ChunkSectionSnapshot snapshot;
        private final long captureSequence;

        private SectionRecord(ChunkSectionSnapshot snapshot, long captureSequence) {
            this.snapshot = snapshot;
            this.captureSequence = captureSequence;
        }
    }

    private static final class BlockOverride {
        private final int stateId;
        private final long sequence;

        private BlockOverride(int stateId, long sequence) {
            this.stateId = stateId;
            this.sequence = sequence;
        }
    }

    public static final class ObservedBlockValue {
        private static final ObservedBlockValue UNKNOWN = new ObservedBlockValue(
            Availability.UNKNOWN,
            -1,
            -1L,
            -1L
        );

        private final Availability availability;
        private final int stateId;
        private final long captureSequence;
        private final long loadGeneration;

        private ObservedBlockValue(
            Availability availability,
            int stateId,
            long captureSequence,
            long loadGeneration
        ) {
            this.availability = availability;
            this.stateId = stateId;
            this.captureSequence = captureSequence;
            this.loadGeneration = loadGeneration;
        }

        public static ObservedBlockValue unknown() {
            return UNKNOWN;
        }

        public Availability getAvailability() {
            return availability;
        }

        public int getStateId() {
            return stateId;
        }

        public long getCaptureSequence() {
            return captureSequence;
        }

        public long getLoadGeneration() {
            return loadGeneration;
        }

        public boolean isKnownAir() {
            return availability == Availability.KNOWN
                && stateId == ChunkSectionSnapshot.AIR_STATE_ID;
        }
    }

    public static final class ApplyResult {
        private final ChunkSnapshotEvent event;
        private final ApplyOutcome outcome;
        private final ChunkStatus status;
        private final int coveredSections;

        private ApplyResult(
            ChunkSnapshotEvent event,
            ApplyOutcome outcome,
            ChunkStatus status,
            int coveredSections
        ) {
            this.event = event;
            this.outcome = outcome;
            this.status = status;
            this.coveredSections = coveredSections;
        }

        public ChunkSnapshotEvent getEvent() {
            return event;
        }

        public ApplyOutcome getOutcome() {
            return outcome;
        }

        public ChunkStatus getStatus() {
            return status;
        }

        public int getCoveredSections() {
            return coveredSections;
        }
    }

    public static final class StoreSnapshot {
        private final int trackedChunks;
        private final int capturingChunks;
        private final int completeChunks;
        private final int partialChunks;
        private final int staleChunks;
        private final int coveredSections;
        private final long completedSnapshots;
        private final long abortedSnapshots;
        private final long partialSnapshots;
        private final long rejectedOldGenerations;
        private final long rejectedOutOfOrder;
        private final long appliedSections;
        private final long appliedBlockUpdates;

        private StoreSnapshot(
            int trackedChunks,
            int capturingChunks,
            int completeChunks,
            int partialChunks,
            int staleChunks,
            int coveredSections,
            long completedSnapshots,
            long abortedSnapshots,
            long partialSnapshots,
            long rejectedOldGenerations,
            long rejectedOutOfOrder,
            long appliedSections,
            long appliedBlockUpdates
        ) {
            this.trackedChunks = trackedChunks;
            this.capturingChunks = capturingChunks;
            this.completeChunks = completeChunks;
            this.partialChunks = partialChunks;
            this.staleChunks = staleChunks;
            this.coveredSections = coveredSections;
            this.completedSnapshots = completedSnapshots;
            this.abortedSnapshots = abortedSnapshots;
            this.partialSnapshots = partialSnapshots;
            this.rejectedOldGenerations = rejectedOldGenerations;
            this.rejectedOutOfOrder = rejectedOutOfOrder;
            this.appliedSections = appliedSections;
            this.appliedBlockUpdates = appliedBlockUpdates;
        }

        public int getTrackedChunks() {
            return trackedChunks;
        }

        public int getCapturingChunks() {
            return capturingChunks;
        }

        public int getCompleteChunks() {
            return completeChunks;
        }

        public int getPartialChunks() {
            return partialChunks;
        }

        public int getStaleChunks() {
            return staleChunks;
        }

        public int getCoveredSections() {
            return coveredSections;
        }

        public long getCompletedSnapshots() {
            return completedSnapshots;
        }

        public long getAbortedSnapshots() {
            return abortedSnapshots;
        }

        public long getPartialSnapshots() {
            return partialSnapshots;
        }

        public long getRejectedOldGenerations() {
            return rejectedOldGenerations;
        }

        public long getRejectedOutOfOrder() {
            return rejectedOutOfOrder;
        }

        public long getAppliedSections() {
            return appliedSections;
        }

        public long getAppliedBlockUpdates() {
            return appliedBlockUpdates;
        }
    }
}
