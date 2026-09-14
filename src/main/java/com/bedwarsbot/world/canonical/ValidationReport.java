package com.bedwarsbot.world.canonical;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValidationReport {
    private final List<ValidationIssue> errors;

    public ValidationReport(List<ValidationIssue> errors) {
        List<ValidationIssue> copy = new ArrayList<ValidationIssue>(errors);
        Collections.sort(copy);
        this.errors = Collections.unmodifiableList(copy);
    }

    public boolean isValid() { return errors.isEmpty(); }
    public List<ValidationIssue> getErrors() { return errors; }
    public int getErrorCount() { return errors.size(); }
}
