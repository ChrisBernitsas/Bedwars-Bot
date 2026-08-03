package com.bedwarsbot.control;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class InputFrameTest {
    @Test
    public void neutralFrameContainsNoInput() {
        assertTrue(InputFrame.neutral().isNeutral());
        assertEquals("none", InputFrame.neutral().toCompactString());
    }

    @Test
    public void builtFrameIsUnaffectedByLaterBuilderChanges() {
        InputFrame.Builder builder = InputFrame.builder().forward(true).sprint(true);
        InputFrame original = builder.build();

        builder.forward(false).hotbarSlot(2);

        assertTrue(original.isForward());
        assertTrue(original.isSprint());
        assertEquals(InputFrame.NO_HOTBAR_SELECTION, original.getHotbarSlot());
        assertEquals("forward+sprint", original.toCompactString());
    }

    @Test
    public void copyBuilderCreatesDistinctImmutableValue() {
        InputFrame movement = InputFrame.builder().left(true).jump(true).build();
        InputFrame withHotbar = InputFrame.builder(movement).hotbarSlot(4).build();

        assertNotEquals(movement, withHotbar);
        assertEquals("left+jump", movement.toCompactString());
        assertEquals("left+jump+hotbar=5", withHotbar.toCompactString());
        assertEquals(movement, withHotbar.withoutHotbarSelection());
        assertFalse(withHotbar.isNeutral());
    }
}
