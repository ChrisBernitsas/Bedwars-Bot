package com.bedwarsbot.hud;

import java.util.concurrent.atomic.AtomicReference;

import com.bedwarsbot.observation.BlockPosition;
import com.bedwarsbot.observation.ObservationHudSnapshot;
import com.bedwarsbot.observation.SparseBlockOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class DebugHud {
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final int WARNING_COLOR = 0xFFAA00;
    private static final double NEARBY_RADIUS_SQUARED = 24.0D * 24.0D;

    private final AtomicReference<HudSnapshot> snapshotReference;
    private final AtomicReference<ObservationHudSnapshot> observationSnapshotReference;

    public DebugHud(
        AtomicReference<HudSnapshot> snapshotReference,
        AtomicReference<ObservationHudSnapshot> observationSnapshotReference
    ) {
        if (snapshotReference == null || observationSnapshotReference == null) {
            throw new IllegalArgumentException("HUD snapshot references must not be null");
        }
        this.snapshotReference = snapshotReference;
        this.observationSnapshotReference = observationSnapshotReference;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) {
            return;
        }

        HudSnapshot snapshot = snapshotReference.get();
        if (snapshot == null) {
            return;
        }

        FontRenderer font = Minecraft.getMinecraft().fontRendererObj;
        int y = 4;
        font.drawStringWithShadow(
            "Bedwars Bot | mode=" + snapshot.getMode()
                + " | safety=" + snapshot.getSafetyStatus(),
            4,
            y,
            TEXT_COLOR
        );
        y += 10;
        font.drawStringWithShadow(
            "proposed=" + snapshot.getProposedFrame().toCompactString(),
            4,
            y,
            TEXT_COLOR
        );
        y += 10;
        font.drawStringWithShadow(
            "active=" + snapshot.getActiveFrame().toCompactString(),
            4,
            y,
            TEXT_COLOR
        );
        y += 10;
        font.drawStringWithShadow(
            "loop=" + snapshot.getControlLoopMicros() + "us | log="
                + snapshot.getLoggerQueueDepth() + '/' + snapshot.getLoggerQueueCapacity()
                + " dropped=" + snapshot.getDroppedLogRecords(),
            4,
            y,
            TEXT_COLOR
        );
        if (snapshot.getLoggerFailure() != null) {
            y += 10;
            font.drawStringWithShadow(
                "logger failure=" + snapshot.getLoggerFailure(),
                4,
                y,
                WARNING_COLOR
            );
        }

        ObservationHudSnapshot observation = observationSnapshotReference.get();
        if (observation == null) {
            return;
        }
        SparseBlockOverlay.Snapshot overlay = observation.getOverlaySnapshot();
        y += 10;
        font.drawStringWithShadow(
            "observed chunks=" + overlay.getLoadedChunkCount()
                + " loads=" + overlay.getObservedChunkLoads()
                + " unloads=" + overlay.getObservedChunkUnloads()
                + " blocks=" + overlay.getObservedBlockEvents(),
            4,
            y,
            TEXT_COLOR
        );
        y += 10;
        font.drawStringWithShadow(
            "overlay=" + overlay.getOverlaySize()
                + " known=" + overlay.getKnownCount()
                + " stale=" + overlay.getStaleCount()
                + " dup=" + overlay.getDuplicateBlockEvents(),
            4,
            y,
            TEXT_COLOR
        );
        y += 10;
        font.drawStringWithShadow(
            "obs queue=" + observation.getQueueDepth() + '/' + observation.getQueueCapacity()
                + " dropped=" + observation.getDroppedEvents()
                + " processed=" + observation.getProcessedEvents()
                + " last/avg/max=" + nanosToMicros(observation.getLastProcessingNanos())
                + '/' + nanosToMicros(observation.getAverageProcessingNanos())
                + '/' + nanosToMicros(observation.getMaxProcessingNanos()) + "us",
            4,
            y,
            observation.getDroppedEvents() == 0L ? TEXT_COLOR : WARNING_COLOR
        );
        y += 10;
        SparseBlockOverlay.RecentChange nearby = findNearbyChange(overlay);
        String nearbyText = "nearby change=none within 24 blocks";
        if (nearby != null) {
            nearbyText = "nearby change=" + nearby.getPosition().toCompactString()
                + ' ' + nearby.getValue().getBlockState().toCompactString()
                + " [" + nearby.getValue().getAvailability() + ']';
        }
        font.drawStringWithShadow(nearbyText, 4, y, TEXT_COLOR);

        if (observation.getFailureMessage() != null) {
            y += 10;
            font.drawStringWithShadow(
                "observation failures=" + observation.getFailureCount()
                    + " last=" + observation.getFailureMessage(),
                4,
                y,
                WARNING_COLOR
            );
        }
    }

    private static SparseBlockOverlay.RecentChange findNearbyChange(
        SparseBlockOverlay.Snapshot overlay
    ) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null) {
            return null;
        }
        int playerDimension = minecraft.thePlayer.dimension;
        for (SparseBlockOverlay.RecentChange change : overlay.getRecentChanges()) {
            BlockPosition position = change.getPosition();
            if (position.getDimension() != playerDimension) {
                continue;
            }
            double deltaX = position.getX() + 0.5D - minecraft.thePlayer.posX;
            double deltaY = position.getY() + 0.5D - minecraft.thePlayer.posY;
            double deltaZ = position.getZ() + 0.5D - minecraft.thePlayer.posZ;
            if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
                <= NEARBY_RADIUS_SQUARED) {
                return change;
            }
        }
        return null;
    }

    private static long nanosToMicros(long nanos) {
        return nanos / 1_000L;
    }
}
