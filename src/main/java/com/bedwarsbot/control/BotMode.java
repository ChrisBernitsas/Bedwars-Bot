package com.bedwarsbot.control;

public enum BotMode {
    DISABLED(false),
    OBSERVE(false),
    SHADOW(false),
    ASSIST(true),
    AUTONOMOUS(true);

    private final boolean inputExecutionAllowed;

    BotMode(boolean inputExecutionAllowed) {
        this.inputExecutionAllowed = inputExecutionAllowed;
    }

    public boolean isInputExecutionAllowed() {
        return inputExecutionAllowed;
    }
}
