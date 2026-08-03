package com.bedwarsbot.verification;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public final class VerificationMarkerContext {
    private static final VerificationMarkerContext EMPTY =
        new VerificationMarkerContext(Collections.<String, String>emptyMap());

    private final SortedMap<String, String> details;

    public VerificationMarkerContext(Map<String, String> details) {
        if (details == null) {
            throw new IllegalArgumentException("details must not be null");
        }
        TreeMap<String, String> copiedDetails = new TreeMap<String, String>();
        for (Map.Entry<String, String> entry : details.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null) {
                throw new IllegalArgumentException("context details must contain text keys and values");
            }
            copiedDetails.put(entry.getKey(), entry.getValue());
        }
        this.details = Collections.unmodifiableSortedMap(copiedDetails);
    }

    public static VerificationMarkerContext empty() {
        return EMPTY;
    }

    public SortedMap<String, String> getDetails() {
        return details;
    }
}
