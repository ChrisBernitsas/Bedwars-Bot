package com.bedwarsbot.control;

public final class ActionSafetyGate {
    public static final long DEFAULT_HOTBAR_INTERVAL_NANOS = 100_000_000L;

    private final long minimumHotbarIntervalNanos;

    private boolean hasAcceptedHotbarSelection;
    private long lastHotbarSelectionNanos;
    private int lastRequestedHotbarSlot = InputFrame.NO_HOTBAR_SELECTION;

    public ActionSafetyGate() {
        this(DEFAULT_HOTBAR_INTERVAL_NANOS);
    }

    public ActionSafetyGate(long minimumHotbarIntervalNanos) {
        if (minimumHotbarIntervalNanos < 0L) {
            throw new IllegalArgumentException("minimumHotbarIntervalNanos must be non-negative");
        }
        this.minimumHotbarIntervalNanos = minimumHotbarIntervalNanos;
    }

    public synchronized Decision evaluate(
        BotMode mode,
        InputFrame proposedFrame,
        boolean clientContextAllowsControl,
        long nowNanos
    ) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (proposedFrame == null) {
            throw new IllegalArgumentException("proposedFrame must not be null");
        }

        if (!mode.isInputExecutionAllowed()) {
            lastRequestedHotbarSlot = InputFrame.NO_HOTBAR_SELECTION;
            return Decision.blocked(Status.BLOCKED_MODE);
        }
        if (!clientContextAllowsControl) {
            lastRequestedHotbarSlot = InputFrame.NO_HOTBAR_SELECTION;
            return Decision.blocked(Status.BLOCKED_CLIENT_CONTEXT);
        }
        if (proposedFrame.getHotbarSlot() < InputFrame.NO_HOTBAR_SELECTION
            || proposedFrame.getHotbarSlot() > 8) {
            lastRequestedHotbarSlot = InputFrame.NO_HOTBAR_SELECTION;
            return Decision.blocked(Status.REJECTED_HOTBAR_SLOT);
        }
        if ((proposedFrame.isForward() && proposedFrame.isBackward())
            || (proposedFrame.isLeft() && proposedFrame.isRight())) {
            lastRequestedHotbarSlot = InputFrame.NO_HOTBAR_SELECTION;
            return Decision.blocked(Status.REJECTED_MOVEMENT_CONFLICT);
        }
        if (proposedFrame.isNeutral()) {
            lastRequestedHotbarSlot = InputFrame.NO_HOTBAR_SELECTION;
            return new Decision(Status.IDLE, InputFrame.neutral());
        }

        int requestedHotbarSlot = proposedFrame.getHotbarSlot();
        if (requestedHotbarSlot == InputFrame.NO_HOTBAR_SELECTION) {
            lastRequestedHotbarSlot = InputFrame.NO_HOTBAR_SELECTION;
            return new Decision(Status.ACCEPTED, proposedFrame);
        }
        if (requestedHotbarSlot == lastRequestedHotbarSlot) {
            return new Decision(Status.ACCEPTED, proposedFrame);
        }
        if (hasAcceptedHotbarSelection
            && nowNanos - lastHotbarSelectionNanos < minimumHotbarIntervalNanos) {
            return new Decision(Status.LIMITED_HOTBAR_RATE, proposedFrame.withoutHotbarSelection());
        }

        hasAcceptedHotbarSelection = true;
        lastHotbarSelectionNanos = nowNanos;
        lastRequestedHotbarSlot = requestedHotbarSlot;
        return new Decision(Status.ACCEPTED, proposedFrame);
    }

    public synchronized void reset() {
        hasAcceptedHotbarSelection = false;
        lastHotbarSelectionNanos = 0L;
        lastRequestedHotbarSlot = InputFrame.NO_HOTBAR_SELECTION;
    }

    public enum Status {
        IDLE,
        ACCEPTED,
        BLOCKED_MODE,
        BLOCKED_CLIENT_CONTEXT,
        REJECTED_MOVEMENT_CONFLICT,
        REJECTED_HOTBAR_SLOT,
        LIMITED_HOTBAR_RATE,
        MANUAL_OVERRIDE,
        INTERNAL_FAILURE
    }

    public static final class Decision {
        private final Status status;
        private final InputFrame permittedFrame;

        private Decision(Status status, InputFrame permittedFrame) {
            this.status = status;
            this.permittedFrame = permittedFrame;
        }

        static Decision blocked(Status status) {
            return new Decision(status, InputFrame.neutral());
        }

        public Status getStatus() {
            return status;
        }

        public InputFrame getPermittedFrame() {
            return permittedFrame;
        }

        public boolean isFullyAccepted() {
            return status == Status.ACCEPTED || status == Status.IDLE;
        }
    }
}
