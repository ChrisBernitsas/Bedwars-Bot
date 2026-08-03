package com.bedwarsbot.control;

public final class BotModeStateMachine {
    private volatile BotMode currentMode = BotMode.DISABLED;

    public BotMode getCurrentMode() {
        return currentMode;
    }

    public synchronized Transition transitionTo(BotMode targetMode) {
        if (targetMode == null) {
            throw new IllegalArgumentException("targetMode must not be null");
        }

        BotMode previousMode = currentMode;
        currentMode = targetMode;
        return new Transition(previousMode, targetMode);
    }

    public Transition disable() {
        return transitionTo(BotMode.DISABLED);
    }

    public static final class Transition {
        private final BotMode previousMode;
        private final BotMode currentMode;

        private Transition(BotMode previousMode, BotMode currentMode) {
            this.previousMode = previousMode;
            this.currentMode = currentMode;
        }

        public BotMode getPreviousMode() {
            return previousMode;
        }

        public BotMode getCurrentMode() {
            return currentMode;
        }

        public boolean isChanged() {
            return previousMode != currentMode;
        }
    }
}
