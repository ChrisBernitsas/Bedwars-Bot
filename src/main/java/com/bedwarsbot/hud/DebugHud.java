package com.bedwarsbot.hud;

import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class DebugHud {
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final int WARNING_COLOR = 0xFFAA00;

    private final AtomicReference<HudSnapshot> snapshotReference;

    public DebugHud(AtomicReference<HudSnapshot> snapshotReference) {
        if (snapshotReference == null) {
            throw new IllegalArgumentException("snapshotReference must not be null");
        }
        this.snapshotReference = snapshotReference;
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
    }
}
