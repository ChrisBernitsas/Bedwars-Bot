package com.bedwarsbot.observation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IncrementalChunkSnapshotScheduler {
    public enum ScheduleOutcome {
        SCHEDULED,
        DUPLICATE,
        PENDING_CAPACITY_EXCEEDED
    }

    public enum CaptureStatus {
        CAPTURING,
        COMPLETE,
        PARTIAL
    }

    private final int pendingCapacity;
    private final Map<ChunkSnapshotKey, LoadedState> loaded =
        new LinkedHashMap<ChunkSnapshotKey, LoadedState>();
    private final LinkedHashMap<ChunkSnapshotKey, CaptureProgress> pending =
        new LinkedHashMap<ChunkSnapshotKey, CaptureProgress>();

    private long nextGeneration = 1L;

    public IncrementalChunkSnapshotScheduler(int pendingCapacity) {
        if (pendingCapacity <= 0) {
            throw new IllegalArgumentException("pendingCapacity must be positive");
        }
        this.pendingCapacity = pendingCapacity;
    }

    public synchronized ScheduleResult schedule(ChunkSnapshotKey key) {
        requireKey(key);
        LoadedState existing = loaded.get(key);
        if (existing != null) {
            return new ScheduleResult(
                ScheduleOutcome.DUPLICATE,
                key,
                existing.generation,
                existing.capturedSections,
                existing.status
            );
        }

        long generation = nextGeneration++;
        if (pending.size() >= pendingCapacity) {
            LoadedState dropped = new LoadedState(generation, CaptureStatus.PARTIAL, 0);
            loaded.put(key, dropped);
            return new ScheduleResult(
                ScheduleOutcome.PENDING_CAPACITY_EXCEEDED,
                key,
                generation,
                0,
                CaptureStatus.PARTIAL
            );
        }

        LoadedState state = new LoadedState(generation, CaptureStatus.CAPTURING, 0);
        loaded.put(key, state);
        pending.put(key, new CaptureProgress(generation));
        return new ScheduleResult(
            ScheduleOutcome.SCHEDULED,
            key,
            generation,
            0,
            CaptureStatus.CAPTURING
        );
    }

    public synchronized CaptureStep peekNextSection() {
        Iterator<Map.Entry<ChunkSnapshotKey, CaptureProgress>> iterator =
            pending.entrySet().iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        Map.Entry<ChunkSnapshotKey, CaptureProgress> next = iterator.next();
        CaptureProgress progress = next.getValue();
        return new CaptureStep(next.getKey(), progress.generation, progress.nextSection);
    }

    public synchronized CaptureAdvance sectionCaptured(CaptureStep step) {
        if (step == null) {
            throw new IllegalArgumentException("step must not be null");
        }
        CaptureProgress progress = pending.get(step.key);
        LoadedState state = loaded.get(step.key);
        if (progress == null || state == null
            || progress.generation != step.generation
            || progress.nextSection != step.sectionIndex) {
            return CaptureAdvance.rejected();
        }

        progress.nextSection++;
        state.capturedSections = progress.nextSection;
        boolean complete = progress.nextSection == ChunkSectionSnapshot.SECTIONS_PER_CHUNK;
        if (complete) {
            pending.remove(step.key);
            state.status = CaptureStatus.COMPLETE;
        }
        return new CaptureAdvance(true, complete, state.capturedSections);
    }

    public synchronized AbortResult abort(ChunkSnapshotKey key, long generation) {
        requireKey(key);
        LoadedState state = loaded.get(key);
        if (state == null || state.generation != generation) {
            return AbortResult.notCurrent(key, generation);
        }
        pending.remove(key);
        state.status = CaptureStatus.PARTIAL;
        return new AbortResult(true, key, generation, state.capturedSections, state.status);
    }

    public synchronized UnloadResult unload(ChunkSnapshotKey key) {
        requireKey(key);
        LoadedState state = loaded.remove(key);
        pending.remove(key);
        if (state == null) {
            return UnloadResult.notLoaded(key);
        }
        return new UnloadResult(
            true,
            key,
            state.generation,
            state.capturedSections,
            state.status
        );
    }

    public synchronized List<UnloadResult> unloadDimension(int dimension) {
        List<ChunkSnapshotKey> keys = new ArrayList<ChunkSnapshotKey>();
        for (ChunkSnapshotKey key : loaded.keySet()) {
            if (key.getDimension() == dimension) {
                keys.add(key);
            }
        }
        List<UnloadResult> results = new ArrayList<UnloadResult>(keys.size());
        for (ChunkSnapshotKey key : keys) {
            results.add(unload(key));
        }
        return results;
    }

    public synchronized Long currentGeneration(ChunkSnapshotKey key) {
        LoadedState state = loaded.get(key);
        return state == null ? null : Long.valueOf(state.generation);
    }

    public synchronized int getPendingChunkCount() {
        return pending.size();
    }

    public synchronized int getPendingSectionCount() {
        int total = 0;
        for (CaptureProgress progress : pending.values()) {
            total += ChunkSectionSnapshot.SECTIONS_PER_CHUNK - progress.nextSection;
        }
        return total;
    }

    public int getPendingCapacity() {
        return pendingCapacity;
    }

    private static void requireKey(ChunkSnapshotKey key) {
        if (key == null) {
            throw new IllegalArgumentException("chunk key must not be null");
        }
    }

    private static final class LoadedState {
        private final long generation;
        private CaptureStatus status;
        private int capturedSections;

        private LoadedState(long generation, CaptureStatus status, int capturedSections) {
            this.generation = generation;
            this.status = status;
            this.capturedSections = capturedSections;
        }
    }

    private static final class CaptureProgress {
        private final long generation;
        private int nextSection;

        private CaptureProgress(long generation) {
            this.generation = generation;
        }
    }

    public static final class ScheduleResult {
        private final ScheduleOutcome outcome;
        private final ChunkSnapshotKey key;
        private final long generation;
        private final int capturedSections;
        private final CaptureStatus status;

        private ScheduleResult(
            ScheduleOutcome outcome,
            ChunkSnapshotKey key,
            long generation,
            int capturedSections,
            CaptureStatus status
        ) {
            this.outcome = outcome;
            this.key = key;
            this.generation = generation;
            this.capturedSections = capturedSections;
            this.status = status;
        }

        public ScheduleOutcome getOutcome() {
            return outcome;
        }

        public ChunkSnapshotKey getKey() {
            return key;
        }

        public long getGeneration() {
            return generation;
        }

        public int getCapturedSections() {
            return capturedSections;
        }

        public CaptureStatus getStatus() {
            return status;
        }

        public boolean isScheduled() {
            return outcome == ScheduleOutcome.SCHEDULED;
        }
    }

    public static final class CaptureStep {
        private final ChunkSnapshotKey key;
        private final long generation;
        private final int sectionIndex;

        private CaptureStep(ChunkSnapshotKey key, long generation, int sectionIndex) {
            this.key = key;
            this.generation = generation;
            this.sectionIndex = sectionIndex;
        }

        public ChunkSnapshotKey getKey() {
            return key;
        }

        public long getGeneration() {
            return generation;
        }

        public int getSectionIndex() {
            return sectionIndex;
        }
    }

    public static final class CaptureAdvance {
        private final boolean accepted;
        private final boolean complete;
        private final int capturedSections;

        private CaptureAdvance(boolean accepted, boolean complete, int capturedSections) {
            this.accepted = accepted;
            this.complete = complete;
            this.capturedSections = capturedSections;
        }

        private static CaptureAdvance rejected() {
            return new CaptureAdvance(false, false, 0);
        }

        public boolean isAccepted() {
            return accepted;
        }

        public boolean isComplete() {
            return complete;
        }

        public int getCapturedSections() {
            return capturedSections;
        }
    }

    public static final class AbortResult {
        private final boolean current;
        private final ChunkSnapshotKey key;
        private final long generation;
        private final int capturedSections;
        private final CaptureStatus status;

        private AbortResult(
            boolean current,
            ChunkSnapshotKey key,
            long generation,
            int capturedSections,
            CaptureStatus status
        ) {
            this.current = current;
            this.key = key;
            this.generation = generation;
            this.capturedSections = capturedSections;
            this.status = status;
        }

        private static AbortResult notCurrent(ChunkSnapshotKey key, long generation) {
            return new AbortResult(false, key, generation, 0, CaptureStatus.PARTIAL);
        }

        public boolean isCurrent() {
            return current;
        }

        public ChunkSnapshotKey getKey() {
            return key;
        }

        public long getGeneration() {
            return generation;
        }

        public int getCapturedSections() {
            return capturedSections;
        }

        public CaptureStatus getStatus() {
            return status;
        }
    }

    public static final class UnloadResult {
        private final boolean loaded;
        private final ChunkSnapshotKey key;
        private final long generation;
        private final int capturedSections;
        private final CaptureStatus status;

        private UnloadResult(
            boolean loaded,
            ChunkSnapshotKey key,
            long generation,
            int capturedSections,
            CaptureStatus status
        ) {
            this.loaded = loaded;
            this.key = key;
            this.generation = generation;
            this.capturedSections = capturedSections;
            this.status = status;
        }

        private static UnloadResult notLoaded(ChunkSnapshotKey key) {
            return new UnloadResult(false, key, 0L, 0, CaptureStatus.PARTIAL);
        }

        public boolean wasLoaded() {
            return loaded;
        }

        public ChunkSnapshotKey getKey() {
            return key;
        }

        public long getGeneration() {
            return generation;
        }

        public int getCapturedSections() {
            return capturedSections;
        }

        public CaptureStatus getStatus() {
            return status;
        }

        public boolean wasCapturing() {
            return loaded && status == CaptureStatus.CAPTURING;
        }
    }
}
