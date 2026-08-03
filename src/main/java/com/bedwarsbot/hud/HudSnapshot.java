package com.bedwarsbot.hud;

import com.bedwarsbot.control.ActionSafetyGate;
import com.bedwarsbot.control.BotMode;
import com.bedwarsbot.control.InputFrame;

public final class HudSnapshot {
    private final BotMode mode;
    private final InputFrame proposedFrame;
    private final InputFrame activeFrame;
    private final ActionSafetyGate.Status safetyStatus;
    private final long controlLoopMicros;
    private final int loggerQueueDepth;
    private final int loggerQueueCapacity;
    private final long droppedLogRecords;
    private final String loggerFailure;

    public HudSnapshot(
        BotMode mode,
        InputFrame proposedFrame,
        InputFrame activeFrame,
        ActionSafetyGate.Status safetyStatus,
        long controlLoopMicros,
        int loggerQueueDepth,
        int loggerQueueCapacity,
        long droppedLogRecords,
        String loggerFailure
    ) {
        this.mode = mode;
        this.proposedFrame = proposedFrame;
        this.activeFrame = activeFrame;
        this.safetyStatus = safetyStatus;
        this.controlLoopMicros = controlLoopMicros;
        this.loggerQueueDepth = loggerQueueDepth;
        this.loggerQueueCapacity = loggerQueueCapacity;
        this.droppedLogRecords = droppedLogRecords;
        this.loggerFailure = loggerFailure;
    }

    public BotMode getMode() {
        return mode;
    }

    public InputFrame getProposedFrame() {
        return proposedFrame;
    }

    public InputFrame getActiveFrame() {
        return activeFrame;
    }

    public ActionSafetyGate.Status getSafetyStatus() {
        return safetyStatus;
    }

    public long getControlLoopMicros() {
        return controlLoopMicros;
    }

    public int getLoggerQueueDepth() {
        return loggerQueueDepth;
    }

    public int getLoggerQueueCapacity() {
        return loggerQueueCapacity;
    }

    public long getDroppedLogRecords() {
        return droppedLogRecords;
    }

    public String getLoggerFailure() {
        return loggerFailure;
    }
}
