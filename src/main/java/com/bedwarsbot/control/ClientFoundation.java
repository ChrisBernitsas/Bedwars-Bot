package com.bedwarsbot.control;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.bedwarsbot.hud.HudSnapshot;
import com.bedwarsbot.logging.AsyncSessionLogger;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ClientFoundation {
    private static final Logger LOGGER = LogManager.getLogger("BedwarsBot");

    private final Minecraft minecraft;
    private final ClientControlState controlState;
    private final AsyncSessionLogger sessionLogger;
    private final AtomicReference<HudSnapshot> hudSnapshot;

    private long clientTick;
    private ActionSafetyGate.Status lastSafetyStatus = ActionSafetyGate.Status.BLOCKED_MODE;
    private InputFrame lastLoggedActiveFrame = InputFrame.neutral();

    public ClientFoundation(
        Minecraft minecraft,
        BotModeStateMachine modeStateMachine,
        ActionSafetyGate safetyGate,
        InputController inputController,
        AsyncSessionLogger sessionLogger,
        String buildVersion
    ) {
        if (minecraft == null
            || modeStateMachine == null
            || safetyGate == null
            || inputController == null
            || sessionLogger == null) {
            throw new IllegalArgumentException("Client foundation dependencies must not be null");
        }
        this.minecraft = minecraft;
        this.controlState = new ClientControlState(modeStateMachine, safetyGate, inputController);
        this.sessionLogger = sessionLogger;
        this.hudSnapshot = new AtomicReference<HudSnapshot>(createHudSnapshot(0L));

        Map<String, String> details = new LinkedHashMap<String, String>();
        details.put("build_version", buildVersion);
        details.put("input_scope", "movement_and_hotbar_only");
        details.put("mode", BotMode.DISABLED.name());
        details.put("queue_capacity", Integer.toString(sessionLogger.getQueueCapacity()));
        sessionLogger.tryLog("session", "session_start", clientTick, null, details);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        long startNanos = System.nanoTime();
        clientTick++;
        try {
            ClientControlState.Update update = controlState.update(
                determineControlContext(),
                startNanos
            );
            logUnsafeContextClear(update);
            InputFrame activeFrame = controlState.getActiveFrame();
            logDecisionIfChanged(update.getDecision(), activeFrame);
            lastSafetyStatus = update.getDecision().getStatus();
            publishHud((System.nanoTime() - startNanos) / 1_000L);
        } catch (RuntimeException failure) {
            handleControlFailure(failure, startNanos);
        }
    }

    public void setMode(BotMode targetMode, String reason) {
        BotModeStateMachine.Transition transition = controlState.transitionTo(targetMode);
        lastSafetyStatus = targetMode.isInputExecutionAllowed()
            ? ActionSafetyGate.Status.IDLE
            : ActionSafetyGate.Status.BLOCKED_MODE;
        logModeTransition(transition, reason);
        publishHud(0L);
    }

    public void setProposedFrame(InputFrame frame, String reason) {
        controlState.setProposedFrame(frame);

        Map<String, String> details = new LinkedHashMap<String, String>();
        details.put("frame", frame.toCompactString());
        details.put("mode", getMode().name());
        details.put("reason", safeReason(reason));
        sessionLogger.tryLog("input", "input_proposed", clientTick, currentWorldTick(), details);
        publishHud(0L);
    }

    public void clearProposedFrame(String reason) {
        controlState.clearProposedFrame();

        Map<String, String> details = new LinkedHashMap<String, String>();
        details.put("frame", InputFrame.neutral().toCompactString());
        details.put("mode", getMode().name());
        details.put("reason", safeReason(reason));
        sessionLogger.tryLog("input", "input_proposed", clientTick, currentWorldTick(), details);
        publishHud(0L);
    }

    public void disableImmediately(String reason) {
        BotModeStateMachine.Transition transition = controlState.disable();
        lastSafetyStatus = ActionSafetyGate.Status.MANUAL_OVERRIDE;

        Map<String, String> details = new LinkedHashMap<String, String>();
        details.put("from", transition.getPreviousMode().name());
        details.put("reason", safeReason(reason));
        sessionLogger.tryLog("manual_override", "control_disabled", clientTick, currentWorldTick(), details);
        publishHud(0L);
    }

    public BotMode getMode() {
        return controlState.getMode();
    }

    public InputFrame getProposedFrame() {
        return controlState.getProposedFrame();
    }

    public InputFrame getActiveFrame() {
        return controlState.getActiveFrame();
    }

    public AtomicReference<HudSnapshot> getHudSnapshotReference() {
        return hudSnapshot;
    }

    public long getClientTick() {
        return clientTick;
    }

    public Long getCurrentWorldTick() {
        return currentWorldTick();
    }

    private ControlContext determineControlContext() {
        if (minecraft.theWorld == null) {
            return ControlContext.WORLD_UNAVAILABLE;
        }
        if (minecraft.thePlayer == null) {
            return ControlContext.PLAYER_UNAVAILABLE;
        }
        if (!minecraft.isSingleplayer()) {
            return ControlContext.NON_LOCAL_SINGLEPLAYER;
        }
        if (minecraft.currentScreen != null) {
            return ControlContext.GUI_OPEN;
        }
        return ControlContext.SAFE;
    }

    private void logUnsafeContextClear(ClientControlState.Update update) {
        if (!update.shouldLogUnsafeContextClear()) {
            return;
        }

        Map<String, String> details = new LinkedHashMap<String, String>();
        details.put("active_inputs_released", Boolean.toString(update.isActiveInputsReleased()));
        details.put("context_changed", Boolean.toString(update.isContextChanged()));
        details.put("mode", getMode().name());
        details.put("proposal_cleared", Boolean.toString(update.isProposalCleared()));
        details.put("reason", update.getContext().name());
        sessionLogger.tryLog(
            "safety_gate",
            "unsafe_context_cleared",
            clientTick,
            currentWorldTick(),
            details
        );
    }

    private void logDecisionIfChanged(
        ActionSafetyGate.Decision decision,
        InputFrame activeFrame
    ) {
        if (decision.getStatus() == lastSafetyStatus && activeFrame.equals(lastLoggedActiveFrame)) {
            return;
        }

        Map<String, String> details = new LinkedHashMap<String, String>();
        details.put("active", activeFrame.toCompactString());
        details.put("mode", getMode().name());
        details.put("permitted", decision.getPermittedFrame().toCompactString());
        details.put("proposed", getProposedFrame().toCompactString());
        details.put("status", decision.getStatus().name());
        sessionLogger.tryLog("safety_gate", "input_decision", clientTick, currentWorldTick(), details);
        lastLoggedActiveFrame = activeFrame;
    }

    private void logModeTransition(BotModeStateMachine.Transition transition, String reason) {
        if (!transition.isChanged()) {
            return;
        }
        Map<String, String> details = new LinkedHashMap<String, String>();
        details.put("from", transition.getPreviousMode().name());
        details.put("reason", safeReason(reason));
        details.put("to", transition.getCurrentMode().name());
        sessionLogger.tryLog("mode", "mode_changed", clientTick, currentWorldTick(), details);
    }

    private void handleControlFailure(RuntimeException failure, long startNanos) {
        try {
            disableImmediately("control_loop_failure");
        } finally {
            lastSafetyStatus = ActionSafetyGate.Status.INTERNAL_FAILURE;
            Map<String, String> details = new LinkedHashMap<String, String>();
            details.put("exception", failure.getClass().getName());
            details.put("message", failure.getMessage() == null ? "" : failure.getMessage());
            sessionLogger.tryLog("control", "control_failure", clientTick, currentWorldTick(), details);
            publishHud((System.nanoTime() - startNanos) / 1_000L);
            LOGGER.error("Bedwars Bot control loop disabled after a failure", failure);
        }
    }

    private Long currentWorldTick() {
        return minecraft.theWorld == null ? null : minecraft.theWorld.getTotalWorldTime();
    }

    private void publishHud(long controlLoopMicros) {
        hudSnapshot.set(createHudSnapshot(controlLoopMicros));
    }

    private HudSnapshot createHudSnapshot(long controlLoopMicros) {
        return new HudSnapshot(
            getMode(),
            getProposedFrame(),
            getActiveFrame(),
            lastSafetyStatus,
            controlLoopMicros,
            sessionLogger.getQueueDepth(),
            sessionLogger.getQueueCapacity(),
            sessionLogger.getDroppedRecords(),
            sessionLogger.getFailureMessage()
        );
    }

    private static String safeReason(String reason) {
        return reason == null || reason.isEmpty() ? "unspecified" : reason;
    }
}
