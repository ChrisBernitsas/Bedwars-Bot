package com.bedwarsbot.verification;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class VerificationMarkerContextTest {
    @Test
    public void defensivelyCopiesContextValues() {
        Map<String, String> mutableSource = new LinkedHashMap<String, String>();
        mutableSource.put("player_dimension", "0");
        mutableSource.put("target_block_x", "12");

        VerificationMarkerContext context = new VerificationMarkerContext(mutableSource);
        mutableSource.put("target_block_x", "999");

        assertEquals("0", context.getDetails().get("player_dimension"));
        assertEquals("12", context.getDetails().get("target_block_x"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void exposesOnlyAnUnmodifiableMap() {
        VerificationMarkerContext.empty().getDetails().put("player_dimension", "0");
    }
}
