package com.bedwarsbot.control;

public enum ControlContext {
    SAFE(true),
    GUI_OPEN(false),
    WORLD_UNAVAILABLE(false),
    PLAYER_UNAVAILABLE(false),
    NON_LOCAL_SINGLEPLAYER(false);

    private final boolean safe;

    ControlContext(boolean safe) {
        this.safe = safe;
    }

    public boolean isSafe() {
        return safe;
    }
}
