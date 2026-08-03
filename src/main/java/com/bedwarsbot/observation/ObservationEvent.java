package com.bedwarsbot.observation;

public final class ObservationEvent {
    public static final int SCHEMA_VERSION = 1;

    public enum Type {
        BLOCK_STATE,
        BLOCK_UNAVAILABLE,
        CHUNK_LOADED,
        CHUNK_UNLOADED,
        DIMENSION_UNLOADED
    }

    private final Type type;
    private final long sequence;
    private final long clientTick;
    private final Long worldTick;
    private final long capturedNanos;
    private final int dimension;
    private final int chunkX;
    private final int chunkZ;
    private final BlockPosition position;
    private final BlockStateSnapshot blockState;

    private ObservationEvent(
        Type type,
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        int dimension,
        int chunkX,
        int chunkZ,
        BlockPosition position,
        BlockStateSnapshot blockState
    ) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if ((type == Type.BLOCK_STATE || type == Type.BLOCK_UNAVAILABLE) && position == null) {
            throw new IllegalArgumentException("block events require a position");
        }
        if (type == Type.BLOCK_STATE && blockState == null) {
            throw new IllegalArgumentException("block-state events require a state");
        }
        if (type != Type.BLOCK_STATE && blockState != null) {
            throw new IllegalArgumentException("only block-state events may include a state");
        }
        this.type = type;
        this.sequence = sequence;
        this.clientTick = clientTick;
        this.worldTick = worldTick;
        this.capturedNanos = capturedNanos;
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.position = position;
        this.blockState = blockState;
    }

    public static ObservationEvent blockState(
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        BlockPosition position,
        BlockStateSnapshot blockState
    ) {
        requirePosition(position);
        return new ObservationEvent(
            Type.BLOCK_STATE,
            sequence,
            clientTick,
            worldTick,
            capturedNanos,
            position.getDimension(),
            position.getChunkX(),
            position.getChunkZ(),
            position,
            blockState
        );
    }

    public static ObservationEvent blockUnavailable(
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        BlockPosition position
    ) {
        requirePosition(position);
        return new ObservationEvent(
            Type.BLOCK_UNAVAILABLE,
            sequence,
            clientTick,
            worldTick,
            capturedNanos,
            position.getDimension(),
            position.getChunkX(),
            position.getChunkZ(),
            position,
            null
        );
    }

    public static ObservationEvent chunkLoaded(
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        int dimension,
        int chunkX,
        int chunkZ
    ) {
        return chunkEvent(
            Type.CHUNK_LOADED,
            sequence,
            clientTick,
            worldTick,
            capturedNanos,
            dimension,
            chunkX,
            chunkZ
        );
    }

    public static ObservationEvent chunkUnloaded(
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        int dimension,
        int chunkX,
        int chunkZ
    ) {
        return chunkEvent(
            Type.CHUNK_UNLOADED,
            sequence,
            clientTick,
            worldTick,
            capturedNanos,
            dimension,
            chunkX,
            chunkZ
        );
    }

    public static ObservationEvent dimensionUnloaded(
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        int dimension
    ) {
        return new ObservationEvent(
            Type.DIMENSION_UNLOADED,
            sequence,
            clientTick,
            worldTick,
            capturedNanos,
            dimension,
            0,
            0,
            null,
            null
        );
    }

    private static ObservationEvent chunkEvent(
        Type type,
        long sequence,
        long clientTick,
        Long worldTick,
        long capturedNanos,
        int dimension,
        int chunkX,
        int chunkZ
    ) {
        return new ObservationEvent(
            type,
            sequence,
            clientTick,
            worldTick,
            capturedNanos,
            dimension,
            chunkX,
            chunkZ,
            null,
            null
        );
    }

    private static void requirePosition(BlockPosition position) {
        if (position == null) {
            throw new IllegalArgumentException("position must not be null");
        }
    }

    public Type getType() {
        return type;
    }

    public long getSequence() {
        return sequence;
    }

    public long getClientTick() {
        return clientTick;
    }

    public Long getWorldTick() {
        return worldTick;
    }

    public long getCapturedNanos() {
        return capturedNanos;
    }

    public int getDimension() {
        return dimension;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public BlockPosition getPosition() {
        return position;
    }

    public BlockStateSnapshot getBlockState() {
        return blockState;
    }
}
