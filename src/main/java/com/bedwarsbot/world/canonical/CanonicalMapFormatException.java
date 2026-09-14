package com.bedwarsbot.world.canonical;

import java.io.IOException;
import java.util.Collections;

public final class CanonicalMapFormatException extends IOException {
    private final ValidationReport validationReport;

    public CanonicalMapFormatException(String code, String path, String message) {
        this(code, path, message, null);
    }

    public CanonicalMapFormatException(
        String code,
        String path,
        String message,
        Throwable cause
    ) {
        super(message, cause);
        this.validationReport = new ValidationReport(Collections.singletonList(
            new ValidationIssue(code, path, message)
        ));
    }

    public CanonicalMapFormatException(ValidationReport report) {
        super(report.getErrors().isEmpty()
            ? "canonical map validation failed"
            : report.getErrors().get(0).toString());
        this.validationReport = report;
    }

    public ValidationReport getValidationReport() {
        return validationReport;
    }
}
