package com.bedwarsbot.control;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BotModeStateMachineTest {
    @Test
    public void startsDisabled() {
        BotModeStateMachine stateMachine = new BotModeStateMachine();

        assertEquals(BotMode.DISABLED, stateMachine.getCurrentMode());
    }

    @Test
    public void explicitlyTransitionsAcrossEveryMode() {
        BotModeStateMachine stateMachine = new BotModeStateMachine();

        for (BotMode mode : BotMode.values()) {
            BotModeStateMachine.Transition transition = stateMachine.transitionTo(mode);

            assertEquals(mode, transition.getCurrentMode());
            assertEquals(mode, stateMachine.getCurrentMode());
        }
    }

    @Test
    public void disableAlwaysReturnsToDisabled() {
        BotModeStateMachine stateMachine = new BotModeStateMachine();
        stateMachine.transitionTo(BotMode.ASSIST);

        BotModeStateMachine.Transition transition = stateMachine.disable();

        assertEquals(BotMode.ASSIST, transition.getPreviousMode());
        assertEquals(BotMode.DISABLED, transition.getCurrentMode());
        assertTrue(transition.isChanged());
        assertFalse(stateMachine.disable().isChanged());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullTransition() {
        new BotModeStateMachine().transitionTo(null);
    }
}
