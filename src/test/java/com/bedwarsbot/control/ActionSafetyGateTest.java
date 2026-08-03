package com.bedwarsbot.control;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ActionSafetyGateTest {
    @Test
    public void blocksAllExecutionInNonControlModes() {
        InputFrame proposed = InputFrame.builder().forward(true).build();

        for (BotMode mode : new BotMode[] {BotMode.DISABLED, BotMode.OBSERVE, BotMode.SHADOW}) {
            ActionSafetyGate.Decision decision = new ActionSafetyGate().evaluate(mode, proposed, true, 1L);

            assertEquals(ActionSafetyGate.Status.BLOCKED_MODE, decision.getStatus());
            assertTrue(decision.getPermittedFrame().isNeutral());
        }
    }

    @Test
    public void blocksControlOutsideSafeClientContext() {
        InputFrame proposed = InputFrame.builder().forward(true).build();

        ActionSafetyGate.Decision decision = new ActionSafetyGate().evaluate(
            BotMode.ASSIST,
            proposed,
            false,
            1L
        );

        assertEquals(ActionSafetyGate.Status.BLOCKED_CLIENT_CONTEXT, decision.getStatus());
        assertTrue(decision.getPermittedFrame().isNeutral());
    }

    @Test
    public void acceptsValidMovementAndHotbarInput() {
        InputFrame proposed = InputFrame.builder()
            .forward(true)
            .sprint(true)
            .hotbarSlot(3)
            .build();

        ActionSafetyGate.Decision decision = new ActionSafetyGate().evaluate(
            BotMode.ASSIST,
            proposed,
            true,
            10L
        );

        assertEquals(ActionSafetyGate.Status.ACCEPTED, decision.getStatus());
        assertEquals(proposed, decision.getPermittedFrame());
    }

    @Test
    public void rejectsConflictingMovement() {
        InputFrame proposed = InputFrame.builder().left(true).right(true).build();

        ActionSafetyGate.Decision decision = new ActionSafetyGate().evaluate(
            BotMode.AUTONOMOUS,
            proposed,
            true,
            10L
        );

        assertEquals(ActionSafetyGate.Status.REJECTED_MOVEMENT_CONFLICT, decision.getStatus());
        assertTrue(decision.getPermittedFrame().isNeutral());
    }

    @Test
    public void rejectsOutOfRangeHotbarSlot() {
        InputFrame proposed = InputFrame.builder().hotbarSlot(9).build();

        ActionSafetyGate.Decision decision = new ActionSafetyGate().evaluate(
            BotMode.ASSIST,
            proposed,
            true,
            10L
        );

        assertEquals(ActionSafetyGate.Status.REJECTED_HOTBAR_SLOT, decision.getStatus());
        assertTrue(decision.getPermittedFrame().isNeutral());
    }

    @Test
    public void limitsRapidChangedHotbarSelectionWithoutDroppingMovement() {
        ActionSafetyGate gate = new ActionSafetyGate(100L);
        InputFrame first = InputFrame.builder().hotbarSlot(0).build();
        InputFrame second = InputFrame.builder().forward(true).hotbarSlot(1).build();

        assertEquals(
            ActionSafetyGate.Status.ACCEPTED,
            gate.evaluate(BotMode.ASSIST, first, true, 1_000L).getStatus()
        );
        gate.evaluate(BotMode.ASSIST, InputFrame.neutral(), true, 1_010L);

        ActionSafetyGate.Decision limited = gate.evaluate(BotMode.ASSIST, second, true, 1_050L);

        assertEquals(ActionSafetyGate.Status.LIMITED_HOTBAR_RATE, limited.getStatus());
        assertTrue(limited.getPermittedFrame().isForward());
        assertEquals(InputFrame.NO_HOTBAR_SELECTION, limited.getPermittedFrame().getHotbarSlot());
        assertEquals(
            ActionSafetyGate.Status.ACCEPTED,
            gate.evaluate(BotMode.ASSIST, second, true, 1_100L).getStatus()
        );
    }
}
