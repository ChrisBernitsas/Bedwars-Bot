package com.bedwarsbot.world.canonical;

public final class ValidationIssue implements Comparable<ValidationIssue> {
    private final String code;
    private final String path;
    private final String message;

    public ValidationIssue(String code, String path, String message) {
        if (code == null || path == null || message == null) {
            throw new IllegalArgumentException("validation issue fields must not be null");
        }
        this.code = code;
        this.path = path;
        this.message = message;
    }

    public String getCode() { return code; }
    public String getPath() { return path; }
    public String getMessage() { return message; }

    @Override
    public int compareTo(ValidationIssue other) {
        int pathComparison = path.compareTo(other.path);
        if (pathComparison != 0) return pathComparison;
        int codeComparison = code.compareTo(other.code);
        return codeComparison != 0 ? codeComparison : message.compareTo(other.message);
    }

    @Override
    public String toString() {
        return code + " at " + path + ": " + message;
    }
}
