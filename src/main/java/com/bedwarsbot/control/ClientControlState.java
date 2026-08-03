package com.bedwarsbot.control;

public final class ClientControlState {
    private final BotModeStateMachine modeStateMachine;
    private final ActionSafetyGate safetyGate;
    private final InputSink inputSink;

    private InputFrame proposedFrame = InputFrame.neutral();
    private ControlContext previousContext;

    public ClientControlState(
        BotModeStateMachine modeStateMachine,
        ActionSafetyGate safetyGate,
        InputSink inputSink
    ) {
        if (modeStateMachine == null || safetyGate == null || inputSink == null) {
            throw new IllegalArgumentException("Control state dependencies must not be null");
        }
        this.modeStateMachine = modeStateMachine;
        this.safetyGate = safetyGate;
        this.inputSink = inputSink;
    }

    public synchronized Update update(ControlContext context, long nowNanos) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }

        boolean contextChanged = previousContext != context;
        previousContext = context;
        if (!context.isSafe()) {
            boolean proposalCleared = !proposedFrame.isNeutral();
            boolean activeInputsReleased = !inputSink.getActiveFrame().isNeutral();
            proposedFrame = InputFrame.neutral();
            safetyGate.reset();
            inputSink.releaseAll();
            return new Update(
                context,
                ActionSafetyGate.Decision.blocked(ActionSafetyGate.Status.BLOCKED_CLIENT_CONTEXT),
                contextChanged,
                proposalCleared,
                activeInputsReleased
            );
        }

        ActionSafetyGate.Decision decision = safetyGate.evaluate(
            modeStateMachine.getCurrentMode(),
            proposedFrame,
            true,
            nowNanos
        );
        inputSink.apply(decision.getPermittedFrame());
        return new Update(context, decision, contextChanged, false, false);
    }

    public synchronized BotModeStateMachine.Transition transitionTo(BotMode targetMode) {
        BotModeStateMachine.Transition transition;
        try {
            transition = modeStateMachine.transitionTo(targetMode);
            proposedFrame = InputFrame.neutral();
            safetyGate.reset();
        } finally {
            inputSink.releaseAll();
        }
        return transition;
    }

    public synchronized BotModeStateMachine.Transition disable() {
        return transitionTo(BotMode.DISABLED);
    }

    public synchronized void setProposedFrame(InputFrame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        proposedFrame = frame;
    }

    public synchronized void clearProposedFrame() {
        proposedFrame = InputFrame.neutral();
        safetyGate.reset();
        inputSink.releaseAll();
    }

    public BotMode getMode() {
        return modeStateMachine.getCurrentMode();
    }

    public synchronized InputFrame getProposedFrame() {
        return proposedFrame;
    }

    public InputFrame getActiveFrame() {
        return inputSink.getActiveFrame();
    }

    public static final class Update {
        private final ControlContext context;
        private final ActionSafetyGate.Decision decision;
        private final boolean contextChanged;
        private final boolean proposalCleared;
        private final boolean activeInputsReleased;

        private Update(
            ControlContext context,
            ActionSafetyGate.Decision decision,
            boolean contextChanged,
            boolean proposalCleared,
            boolean activeInputsReleased
        ) {
            this.context = context;
            this.decision = decision;
            this.contextChanged = contextChanged;
            this.proposalCleared = proposalCleared;
            this.activeInputsReleased = activeInputsReleased;
        }

        public ControlContext getContext() {
            return context;
        }

        public ActionSafetyGate.Decision getDecision() {
            return decision;
        }

        public boolean isContextChanged() {
            return contextChanged;
        }

        public boolean isProposalCleared() {
            return proposalCleared;
        }

        public boolean isActiveInputsReleased() {
            return activeInputsReleased;
        }

        public boolean shouldLogUnsafeContextClear() {
            return !context.isSafe()
                && (contextChanged || proposalCleared || activeInputsReleased);
        }
    }
}
