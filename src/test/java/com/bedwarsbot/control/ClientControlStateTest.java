package com.bedwarsbot.control;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ClientControlStateTest {
    private FakeInputSink inputSink;
    private ClientControlState controlState;

    @Before
    public void setUp() {
        inputSink = new FakeInputSink();
        controlState = new ClientControlState(
            new BotModeStateMachine(),
            new ActionSafetyGate(),
            inputSink
        );
        controlState.transitionTo(BotMode.ASSIST);
    }

    @Test
    public void unsafeContextReleasesActiveInputs() {
        activateForwardInput();

        ClientControlState.Update update = controlState.update(ControlContext.GUI_OPEN, 2L);

        assertTrue(update.isActiveInputsReleased());
        assertTrue(inputSink.getActiveFrame().isNeutral());
        assertEquals(ActionSafetyGate.Status.BLOCKED_CLIENT_CONTEXT, update.getDecision().getStatus());
    }

    @Test
    public void unsafeContextClearsPendingProposalAndRecordsReason() {
        controlState.setProposedFrame(InputFrame.builder().left(true).build());

        ClientControlState.Update update = controlState.update(ControlContext.WORLD_UNAVAILABLE, 1L);

        assertTrue(update.isProposalCleared());
        assertTrue(update.shouldLogUnsafeContextClear());
        assertEquals(ControlContext.WORLD_UNAVAILABLE, update.getContext());
        assertTrue(controlState.getProposedFrame().isNeutral());
    }

    @Test
    public void returningToSafeContextDoesNotResumeEarlierInput() {
        activateForwardInput();
        controlState.update(ControlContext.PLAYER_UNAVAILABLE, 2L);

        ClientControlState.Update safeAgain = controlState.update(ControlContext.SAFE, 3L);

        assertTrue(controlState.getProposedFrame().isNeutral());
        assertTrue(inputSink.getActiveFrame().isNeutral());
        assertEquals(ActionSafetyGate.Status.IDLE, safeAgain.getDecision().getStatus());
        assertFalse(safeAgain.shouldLogUnsafeContextClear());
    }

    @Test
    public void newExplicitProposalIsRequiredAfterReturningSafe() {
        activateForwardInput();
        controlState.update(ControlContext.NON_LOCAL_SINGLEPLAYER, 2L);
        controlState.update(ControlContext.SAFE, 3L);
        assertTrue(inputSink.getActiveFrame().isNeutral());

        InputFrame newProposal = InputFrame.builder().right(true).build();
        controlState.setProposedFrame(newProposal);
        controlState.update(ControlContext.SAFE, 4L);

        assertEquals(newProposal, controlState.getProposedFrame());
        assertEquals(newProposal, inputSink.getActiveFrame());
    }

    private void activateForwardInput() {
        InputFrame forward = InputFrame.builder().forward(true).build();
        controlState.setProposedFrame(forward);
        controlState.update(ControlContext.SAFE, 1L);
        assertEquals(forward, inputSink.getActiveFrame());
    }

    private static final class FakeInputSink implements InputSink {
        private InputFrame activeFrame = InputFrame.neutral();

        @Override
        public void apply(InputFrame permittedFrame) {
            activeFrame = permittedFrame;
        }

        @Override
        public void releaseAll() {
            activeFrame = InputFrame.neutral();
        }

        @Override
        public InputFrame getActiveFrame() {
            return activeFrame;
        }
    }
}
