package com.bedwarsbot.observation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

public final class ClientChunkSnapshotCollector implements AutoCloseable {
    public static final int DEFAULT_PENDING_CHUNK_CAPACITY = 1024;
    public static final int MAX_BLOCKS_PER_TICK = ChunkSectionSnapshot.BLOCKS_PER_SECTION;
    public static final long MAX_SCAN_NANOS_PER_TICK = 1_500_000L;

    private final Minecraft minecraft;
    private final ChunkSnapshotPipeline pipeline;
    private final ObservationSequence sequence;
    private final IncrementalChunkSnapshotScheduler scheduler;
    private final NanoClock scanClock;
    private final Map<ChunkSnapshotKey, PendingChunk> pendingChunks =
        new HashMap<ChunkSnapshotKey, PendingChunk>();
    private final AtomicReference<ChunkSnapshotHudSnapshot> hudSnapshot =
        new AtomicReference<ChunkSnapshotHudSnapshot>();

    private long scheduledLoads;
    private long duplicateLoads;
    private long droppedSchedules;
    private long scannedSections;
    private long scannedBlocks;
    private long scanSamples;
    private int lastBlocksCopiedThisTick;
    private long lastScanNanos;
    private long totalScanNanos;
    private long maxScanNanos;
    private long blockBudgetStopCount;
    private long timeBudgetStopCount;
    private long partialSectionResumeCount;
    private IncrementalScanBudget.StopReason lastScanStopReason =
        IncrementalScanBudget.StopReason.NONE;
    private long lastClientTick;
    private IncrementalSectionCaptureCursor sectionCursor;
    private boolean closed;

    public ClientChunkSnapshotCollector(
        Minecraft minecraft,
        ChunkSnapshotPipeline pipeline,
        ObservationSequence sequence
    ) {
        this(
            minecraft,
            pipeline,
            sequence,
            new IncrementalChunkSnapshotScheduler(DEFAULT_PENDING_CHUNK_CAPACITY),
            NanoClock.SYSTEM
        );
    }

    ClientChunkSnapshotCollector(
        Minecraft minecraft,
        ChunkSnapshotPipeline pipeline,
        ObservationSequence sequence,
        IncrementalChunkSnapshotScheduler scheduler,
        NanoClock scanClock
    ) {
        if (minecraft == null || pipeline == null || sequence == null || scheduler == null
            || scanClock == null) {
            throw new IllegalArgumentException("chunk snapshot collector dependencies must not be null");
        }
        this.minecraft = minecraft;
        this.pipeline = pipeline;
        this.sequence = sequence;
        this.scheduler = scheduler;
        this.scanClock = scanClock;
        publishHudSnapshot();
    }

    public synchronized void onChunkLoaded(
        WorldClient world,
        Chunk chunk,
        long clientTick
    ) {
        if (closed || world == null || chunk == null) {
            return;
        }
        requireClientThread();
        lastClientTick = clientTick;
        ChunkSnapshotKey key = key(world, chunk.xPosition, chunk.zPosition);
        IncrementalChunkSnapshotScheduler.ScheduleResult scheduled = scheduler.schedule(key);
        switch (scheduled.getOutcome()) {
            case SCHEDULED:
                scheduledLoads++;
                break;
            case DUPLICATE:
                duplicateLoads++;
                break;
            case PENDING_CAPACITY_EXCEEDED:
                droppedSchedules++;
                break;
            default:
                throw new IllegalStateException("Unhandled schedule outcome " + scheduled.getOutcome());
        }

        long captureSequence = sequence.next();
        boolean recorded = pipeline.tryCapture(ChunkSnapshotEvent.scheduled(
            captureSequence,
            clientTick,
            Long.valueOf(world.getTotalWorldTime()),
            System.nanoTime(),
            key,
            scheduled.getGeneration(),
            scheduled.getOutcome().name()
        ));
        if (scheduled.isScheduled() && recorded) {
            pendingChunks.put(key, new PendingChunk(world, chunk, scheduled.getGeneration()));
        } else if (scheduled.isScheduled()) {
            droppedSchedules++;
            abortGeneration(
                key,
                scheduled.getGeneration(),
                clientTick,
                Long.valueOf(world.getTotalWorldTime()),
                "snapshot_schedule_queue_full"
            );
        }
        publishHudSnapshot();
    }

    public synchronized void onChunkUnloaded(
        WorldClient world,
        int chunkX,
        int chunkZ,
        long clientTick
    ) {
        if (closed || world == null) {
            return;
        }
        requireClientThread();
        lastClientTick = clientTick;
        ChunkSnapshotKey key = key(world, chunkX, chunkZ);
        IncrementalChunkSnapshotScheduler.UnloadResult unloaded = scheduler.unload(key);
        pendingChunks.remove(key);
        discardSectionCursor(key, unloaded.getGeneration());
        if (!unloaded.wasLoaded()) {
            publishHudSnapshot();
            return;
        }
        Long worldTick = Long.valueOf(world.getTotalWorldTime());
        if (unloaded.wasCapturing()) {
            pipeline.tryCapture(ChunkSnapshotEvent.aborted(
                sequence.next(),
                clientTick,
                worldTick,
                System.nanoTime(),
                key,
                unloaded.getGeneration(),
                unloaded.getCapturedSections(),
                "chunk_unloaded_during_capture"
            ));
        }
        pipeline.tryCapture(ChunkSnapshotEvent.unloaded(
            sequence.next(),
            clientTick,
            worldTick,
            System.nanoTime(),
            key,
            unloaded.getGeneration(),
            unloaded.getCapturedSections()
        ));
        publishHudSnapshot();
    }

    public synchronized void onDimensionUnloaded(WorldClient world, long clientTick) {
        if (closed || world == null) {
            return;
        }
        requireClientThread();
        lastClientTick = clientTick;
        int dimension = world.provider.getDimensionId();
        Long worldTick = Long.valueOf(world.getTotalWorldTime());
        List<IncrementalChunkSnapshotScheduler.UnloadResult> unloaded =
            scheduler.unloadDimension(dimension);
        for (IncrementalChunkSnapshotScheduler.UnloadResult result : unloaded) {
            pendingChunks.remove(result.getKey());
            discardSectionCursor(result.getKey(), result.getGeneration());
            if (result.wasCapturing()) {
                pipeline.tryCapture(ChunkSnapshotEvent.aborted(
                    sequence.next(),
                    clientTick,
                    worldTick,
                    System.nanoTime(),
                    result.getKey(),
                    result.getGeneration(),
                    result.getCapturedSections(),
                    "dimension_unloaded_during_capture"
                ));
            }
        }
        pipeline.tryCapture(ChunkSnapshotEvent.dimensionUnloaded(
            sequence.next(),
            clientTick,
            worldTick,
            System.nanoTime(),
            dimension,
            0L
        ));
        publishHudSnapshot();
    }

    public synchronized void onBlockStateObserved(
        BlockPosition position,
        int blockStateId,
        long captureSequence,
        long clientTick,
        Long worldTick,
        long capturedNanos
    ) {
        if (closed || position == null) {
            return;
        }
        requireClientThread();
        if (blockStateId < 0 || blockStateId > Character.MAX_VALUE) {
            pipeline.recordCaptureFailure(new IllegalArgumentException(
                "observed block state ID does not fit in the snapshot encoding"
            ));
            return;
        }
        ChunkSnapshotKey key = new ChunkSnapshotKey(
            position.getDimension(),
            position.getChunkX(),
            position.getChunkZ()
        );
        Long generation = scheduler.currentGeneration(key);
        if (generation == null) {
            return;
        }
        if (sectionCursor != null
            && position.getY() >= 0
            && position.getY() < 256
            && sectionCursor.matches(key, generation.longValue(), position.getY() >> 4)) {
            int localIndex = (position.getY() & 15) << 8
                | (position.getZ() & 15) << 4
                | position.getX() & 15;
            sectionCursor.patchIfCopied(localIndex, blockStateId);
        }
        boolean accepted = pipeline.tryCapture(ChunkSnapshotEvent.blockStateObserved(
            captureSequence,
            clientTick,
            worldTick,
            capturedNanos,
            key,
            generation.longValue(),
            position,
            blockStateId
        ));
        if (!accepted) {
            abortGeneration(
                key,
                generation.longValue(),
                clientTick,
                worldTick,
                "block_reconciliation_queue_full"
            );
        }
        publishHudSnapshot();
    }

    public synchronized void onClientTick(WorldClient currentWorld, long clientTick) {
        if (closed) {
            return;
        }
        requireClientThread();
        lastClientTick = clientTick;
        IncrementalScanBudget budget = new IncrementalScanBudget(
            MAX_BLOCKS_PER_TICK,
            MAX_SCAN_NANOS_PER_TICK,
            scanClock
        );
        ChunkSnapshotKey metricKey = null;
        long metricGeneration = 0L;
        boolean attemptedWork = false;
        boolean resumeCountedThisTick = false;

        while (true) {
            IncrementalChunkSnapshotScheduler.CaptureStep step = scheduler.peekNextSection();
            if (step == null) {
                break;
            }
            metricKey = step.getKey();
            metricGeneration = step.getGeneration();
            attemptedWork = true;
            if (!budget.hasBudgetRemaining()) {
                break;
            }

            PendingChunk pending = pendingChunks.get(step.getKey());
            if (!isAvailable(currentWorld, pending, step)) {
                abortGeneration(
                    step.getKey(),
                    step.getGeneration(),
                    clientTick,
                    currentWorld == null
                        ? null
                        : Long.valueOf(currentWorld.getTotalWorldTime()),
                    "chunk_unavailable_during_section_capture"
                );
                continue;
            }

            try {
                char[] liveStateIds = liveSectionStateIds(pending, step.getSectionIndex());
                if (sectionCursor == null) {
                    sectionCursor = new IncrementalSectionCaptureCursor(
                        step.getKey(),
                        step.getGeneration(),
                        step.getSectionIndex()
                    );
                } else if (!sectionCursor.matches(
                    step.getKey(),
                    step.getGeneration(),
                    step.getSectionIndex()
                )) {
                    throw new IllegalStateException(
                        "partial section cursor no longer matches scheduler progress"
                    );
                } else if (sectionCursor.getPositionWithinSection() > 0
                    && !resumeCountedThisTick) {
                    partialSectionResumeCount++;
                    resumeCountedThisTick = true;
                }

                sectionCursor.copyFrom(liveStateIds, budget);
                budget.observeEnd();
                if (!sectionCursor.isComplete()) {
                    break;
                }
                publishCompletedSection(currentWorld, clientTick, step, sectionCursor);
                sectionCursor = null;
            } catch (RuntimeException failure) {
                pipeline.recordCaptureFailure(failure);
                abortGeneration(
                    step.getKey(),
                    step.getGeneration(),
                    clientTick,
                    currentWorld == null
                        ? null
                        : Long.valueOf(currentWorld.getTotalWorldTime()),
                    "section_capture_failure"
                );
            }
        }

        if (attemptedWork) {
            recordScanMetrics(budget);
            pipeline.tryCapture(ChunkSnapshotEvent.scanTick(
                sequence.next(),
                clientTick,
                currentWorld == null ? null : Long.valueOf(currentWorld.getTotalWorldTime()),
                System.nanoTime(),
                metricKey,
                metricGeneration,
                lastBlocksCopiedThisTick,
                lastScanNanos,
                lastScanStopReason,
                maxScanNanos,
                partialSectionResumeCount
            ));
        } else {
            lastBlocksCopiedThisTick = 0;
            lastScanNanos = 0L;
            lastScanStopReason = IncrementalScanBudget.StopReason.NONE;
        }
        publishHudSnapshot();
    }

    public AtomicReference<ChunkSnapshotHudSnapshot> getHudSnapshotReference() {
        return hudSnapshot;
    }

    public synchronized ChunkSnapshotHudSnapshot refreshHudSnapshot() {
        publishHudSnapshot();
        return hudSnapshot.get();
    }

    public void recordCaptureFailure(RuntimeException failure) {
        pipeline.recordCaptureFailure(failure);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        sectionCursor = null;
        IncrementalChunkSnapshotScheduler.CaptureStep step = scheduler.peekNextSection();
        while (step != null) {
            IncrementalChunkSnapshotScheduler.AbortResult aborted =
                scheduler.abort(step.getKey(), step.getGeneration());
            pendingChunks.remove(step.getKey());
            if (aborted.isCurrent()) {
                pipeline.tryCapture(ChunkSnapshotEvent.aborted(
                    sequence.next(),
                    lastClientTick,
                    null,
                    System.nanoTime(),
                    aborted.getKey(),
                    aborted.getGeneration(),
                    aborted.getCapturedSections(),
                    "collector_shutdown"
                ));
            }
            step = scheduler.peekNextSection();
        }
        publishHudSnapshot();
    }

    private void publishCompletedSection(
        WorldClient currentWorld,
        long clientTick,
        IncrementalChunkSnapshotScheduler.CaptureStep step,
        IncrementalSectionCaptureCursor completedCursor
    ) {
        ChunkSectionSnapshot copied = completedCursor.completeSnapshot();
        boolean accepted = pipeline.tryCapture(ChunkSnapshotEvent.sectionCaptured(
            sequence.next(),
            clientTick,
            Long.valueOf(currentWorld.getTotalWorldTime()),
            System.nanoTime(),
            step.getKey(),
            step.getGeneration(),
            copied,
            step.getSectionIndex() + 1
        ));
        if (!accepted) {
            abortGeneration(
                step.getKey(),
                step.getGeneration(),
                clientTick,
                Long.valueOf(currentWorld.getTotalWorldTime()),
                "section_capture_queue_full"
            );
            return;
        }

        IncrementalChunkSnapshotScheduler.CaptureAdvance advance =
            scheduler.sectionCaptured(step);
        if (!advance.isAccepted()) {
            throw new IllegalStateException("section capture no longer matches its generation");
        }
        scannedSections++;
        scannedBlocks += ChunkSectionSnapshot.BLOCKS_PER_SECTION;
        if (advance.isComplete()) {
            pendingChunks.remove(step.getKey());
            if (!pipeline.tryCapture(ChunkSnapshotEvent.completed(
                sequence.next(),
                clientTick,
                Long.valueOf(currentWorld.getTotalWorldTime()),
                System.nanoTime(),
                step.getKey(),
                step.getGeneration()
            ))) {
                abortGeneration(
                    step.getKey(),
                    step.getGeneration(),
                    clientTick,
                    Long.valueOf(currentWorld.getTotalWorldTime()),
                    "snapshot_completion_queue_full"
                );
            }
        }
    }

    private static char[] liveSectionStateIds(PendingChunk pending, int sectionIndex) {
        ExtendedBlockStorage[] sections = pending.chunk.getBlockStorageArray();
        if (sections == null || sectionIndex >= sections.length) {
            throw new IllegalStateException("loaded chunk has an invalid section array");
        }
        ExtendedBlockStorage minecraftSection = sections[sectionIndex];
        return minecraftSection == null ? null : minecraftSection.getData();
    }

    private static boolean isAvailable(
        WorldClient currentWorld,
        PendingChunk pending,
        IncrementalChunkSnapshotScheduler.CaptureStep step
    ) {
        return pending != null
            && pending.generation == step.getGeneration()
            && pending.world == currentWorld
            && pending.chunk.isLoaded();
    }

    private void abortGeneration(
        ChunkSnapshotKey key,
        long generation,
        long clientTick,
        Long worldTick,
        String reason
    ) {
        IncrementalChunkSnapshotScheduler.AbortResult aborted = scheduler.abort(key, generation);
        pendingChunks.remove(key);
        discardSectionCursor(key, generation);
        if (aborted.isCurrent()) {
            pipeline.tryCapture(ChunkSnapshotEvent.aborted(
                sequence.next(),
                clientTick,
                worldTick,
                System.nanoTime(),
                key,
                generation,
                aborted.getCapturedSections(),
                reason
            ));
        }
    }

    private void discardSectionCursor(ChunkSnapshotKey key, long generation) {
        if (sectionCursor != null
            && sectionCursor.getChunkKey().equals(key)
            && sectionCursor.getLoadGeneration() == generation) {
            sectionCursor = null;
        }
    }

    private void recordScanMetrics(IncrementalScanBudget budget) {
        lastBlocksCopiedThisTick = budget.getCopiedBlocks();
        lastScanNanos = budget.getElapsedNanos();
        lastScanStopReason = budget.getStopReason();
        totalScanNanos += lastScanNanos;
        scanSamples++;
        if (lastScanNanos > maxScanNanos) {
            maxScanNanos = lastScanNanos;
        }
        if (lastScanStopReason == IncrementalScanBudget.StopReason.BLOCK_BUDGET) {
            blockBudgetStopCount++;
        } else if (lastScanStopReason == IncrementalScanBudget.StopReason.TIME_BUDGET) {
            timeBudgetStopCount++;
        }
    }

    private void publishHudSnapshot() {
        long average = scanSamples == 0L ? 0L : totalScanNanos / scanSamples;
        hudSnapshot.set(new ChunkSnapshotHudSnapshot(
            scheduler.getPendingChunkCount(),
            scheduler.getPendingSectionCount(),
            scheduler.getPendingCapacity(),
            scheduledLoads,
            duplicateLoads,
            droppedSchedules,
            scannedSections,
            scannedBlocks,
            lastBlocksCopiedThisTick,
            lastScanNanos,
            average,
            maxScanNanos,
            lastScanStopReason,
            blockBudgetStopCount,
            timeBudgetStopCount,
            partialSectionResumeCount,
            pipeline.getPipelineSnapshotReference().get()
        ));
    }

    private void requireClientThread() {
        if (!minecraft.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("chunk snapshots must be captured on the client thread");
        }
    }

    private static ChunkSnapshotKey key(WorldClient world, int chunkX, int chunkZ) {
        return new ChunkSnapshotKey(world.provider.getDimensionId(), chunkX, chunkZ);
    }

    private static final class PendingChunk {
        private final WorldClient world;
        private final Chunk chunk;
        private final long generation;

        private PendingChunk(WorldClient world, Chunk chunk, long generation) {
            this.world = world;
            this.chunk = chunk;
            this.generation = generation;
        }
    }
}
