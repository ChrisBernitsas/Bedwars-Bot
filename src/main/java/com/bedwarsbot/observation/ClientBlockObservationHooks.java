package com.bedwarsbot.observation;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.World;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public final class ClientBlockObservationHooks {
    private final Minecraft minecraft;
    private final ObservationPipeline pipeline;
    private final ClientChunkSnapshotCollector chunkSnapshotCollector;
    private final ObservationSequence sequence;

    private WorldClient attachedWorld;
    private WorldAccessObserver attachedObserver;
    private long clientTick;

    public ClientBlockObservationHooks(
        Minecraft minecraft,
        ObservationPipeline pipeline,
        ClientChunkSnapshotCollector chunkSnapshotCollector,
        ObservationSequence sequence
    ) {
        if (minecraft == null
            || pipeline == null
            || chunkSnapshotCollector == null
            || sequence == null) {
            throw new IllegalArgumentException("observation hook dependencies must not be null");
        }
        this.minecraft = minecraft;
        this.pipeline = pipeline;
        this.chunkSnapshotCollector = chunkSnapshotCollector;
        this.sequence = sequence;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        clientTick++;
        try {
            WorldClient currentWorld = minecraft.theWorld;
            if (currentWorld != attachedWorld) {
                if (currentWorld == null) {
                    detachAttachedWorld(true);
                } else {
                    attachWorld(currentWorld);
                }
            }
            chunkSnapshotCollector.onClientTick(currentWorld, clientTick);
        } catch (RuntimeException failure) {
            recordCaptureFailure(failure);
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!isClientWorld(event.world)) {
            return;
        }
        try {
            attachWorld((WorldClient) event.world);
        } catch (RuntimeException failure) {
            recordCaptureFailure(failure);
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world != attachedWorld) {
            return;
        }
        try {
            captureDimensionUnloaded(attachedWorld);
            detachAttachedWorld(false);
        } catch (RuntimeException failure) {
            recordCaptureFailure(failure);
            attachedWorld = null;
            attachedObserver = null;
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!isClientWorld(event.world)) {
            return;
        }
        try {
            WorldClient world = (WorldClient) event.world;
            attachWorld(world);
            long captureSequence = sequence.next();
            pipeline.tryCapture(ObservationEvent.chunkLoaded(
                captureSequence,
                clientTick,
                world.getTotalWorldTime(),
                System.nanoTime(),
                dimension(world),
                event.getChunk().xPosition,
                event.getChunk().zPosition
            ));
            chunkSnapshotCollector.onChunkLoaded(world, event.getChunk(), clientTick);
        } catch (RuntimeException failure) {
            recordCaptureFailure(failure);
        }
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (!isClientWorld(event.world)) {
            return;
        }
        try {
            WorldClient world = (WorldClient) event.world;
            long captureSequence = sequence.next();
            pipeline.tryCapture(ObservationEvent.chunkUnloaded(
                captureSequence,
                clientTick,
                world.getTotalWorldTime(),
                System.nanoTime(),
                dimension(world),
                event.getChunk().xPosition,
                event.getChunk().zPosition
            ));
            chunkSnapshotCollector.onChunkUnloaded(
                world,
                event.getChunk().xPosition,
                event.getChunk().zPosition,
                clientTick
            );
        } catch (RuntimeException failure) {
            recordCaptureFailure(failure);
        }
    }

    private void attachWorld(WorldClient world) {
        if (world == attachedWorld) {
            return;
        }
        detachAttachedWorld(true);
        WorldAccessObserver observer = new WorldAccessObserver(world);
        world.addWorldAccess(observer);
        attachedWorld = world;
        attachedObserver = observer;
    }

    private void detachAttachedWorld(boolean captureUnload) {
        WorldClient world = attachedWorld;
        WorldAccessObserver observer = attachedObserver;
        if (world == null) {
            return;
        }
        if (captureUnload) {
            captureDimensionUnloaded(world);
        }
        if (observer != null) {
            world.removeWorldAccess(observer);
        }
        attachedWorld = null;
        attachedObserver = null;
    }

    private void captureDimensionUnloaded(WorldClient world) {
        long captureSequence = sequence.next();
        pipeline.tryCapture(ObservationEvent.dimensionUnloaded(
            captureSequence,
            clientTick,
            world.getTotalWorldTime(),
            System.nanoTime(),
            dimension(world)
        ));
        chunkSnapshotCollector.onDimensionUnloaded(world, clientTick);
    }

    private void captureBlockState(WorldClient world, BlockPos minecraftPosition) {
        try {
            BlockPosition position = new BlockPosition(
                dimension(world),
                minecraftPosition.getX(),
                minecraftPosition.getY(),
                minecraftPosition.getZ()
            );
            long captureSequence = sequence.next();
            long capturedNanos = System.nanoTime();
            long worldTick = world.getTotalWorldTime();
            if (!world.isBlockLoaded(minecraftPosition)) {
                pipeline.tryCapture(ObservationEvent.blockUnavailable(
                    captureSequence,
                    clientTick,
                    worldTick,
                    capturedNanos,
                    position
                ));
                return;
            }

            IBlockState minecraftState = world.getBlockState(minecraftPosition);
            Block block = minecraftState.getBlock();
            Object registryName = Block.blockRegistry.getNameForObject(block);
            BlockStateSnapshot state = new BlockStateSnapshot(
                Block.getIdFromBlock(block),
                registryName == null ? "unregistered:block" : registryName.toString(),
                block.getMetaFromState(minecraftState)
            );
            pipeline.tryCapture(ObservationEvent.blockState(
                captureSequence,
                clientTick,
                worldTick,
                capturedNanos,
                position,
                state
            ));
            int globalStateId = Block.BLOCK_STATE_IDS.get(minecraftState);
            chunkSnapshotCollector.onBlockStateObserved(
                position,
                globalStateId,
                captureSequence,
                clientTick,
                Long.valueOf(worldTick),
                capturedNanos
            );
        } catch (RuntimeException failure) {
            recordCaptureFailure(failure);
        }
    }

    private void recordCaptureFailure(RuntimeException failure) {
        pipeline.recordCaptureFailure(failure);
        chunkSnapshotCollector.recordCaptureFailure(failure);
    }

    private static boolean isClientWorld(World world) {
        return world != null && world.isRemote && world instanceof WorldClient;
    }

    private static int dimension(WorldClient world) {
        return world.provider.getDimensionId();
    }

    private final class WorldAccessObserver implements IWorldAccess {
        private final WorldClient world;

        private WorldAccessObserver(WorldClient world) {
            this.world = world;
        }

        @Override
        public void markBlockForUpdate(BlockPos pos) {
            captureBlockState(world, pos);
        }

        @Override
        public void notifyLightSet(BlockPos pos) {
        }

        @Override
        public void markBlockRangeForRenderUpdate(
            int x1,
            int y1,
            int z1,
            int x2,
            int y2,
            int z2
        ) {
        }

        @Override
        public void playSound(
            String soundName,
            double x,
            double y,
            double z,
            float volume,
            float pitch
        ) {
        }

        @Override
        public void playSoundToNearExcept(
            EntityPlayer except,
            String soundName,
            double x,
            double y,
            double z,
            float volume,
            float pitch
        ) {
        }

        @Override
        public void spawnParticle(
            int particleID,
            boolean ignoreRange,
            double xCoord,
            double yCoord,
            double zCoord,
            double xOffset,
            double yOffset,
            double zOffset,
            int... parameters
        ) {
        }

        @Override
        public void onEntityAdded(Entity entityIn) {
        }

        @Override
        public void onEntityRemoved(Entity entityIn) {
        }

        @Override
        public void playRecord(String recordName, BlockPos blockPosIn) {
        }

        @Override
        public void broadcastSound(int soundID, BlockPos pos, int data) {
        }

        @Override
        public void playAuxSFX(EntityPlayer player, int sfxType, BlockPos pos, int data) {
        }

        @Override
        public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {
        }
    }
}
