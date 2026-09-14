package com.bedwarsbot.world.canonical;

public final class CanonicalMapRegistryException extends IllegalArgumentException {
    private final String code;

    public CanonicalMapRegistryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
