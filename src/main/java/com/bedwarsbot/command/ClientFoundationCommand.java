package com.bedwarsbot.command;

import java.util.Locale;

import com.bedwarsbot.control.BotMode;
import com.bedwarsbot.control.ClientFoundation;
import com.bedwarsbot.control.InputFrame;
import com.bedwarsbot.verification.ClientVerificationContextCapture;
import com.bedwarsbot.verification.VerificationEventLogger;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

public final class ClientFoundationCommand extends CommandBase {
    private final ClientFoundation clientFoundation;
    private final ClientVerificationContextCapture verificationContextCapture;
    private final VerificationEventLogger verificationLogger;

    public ClientFoundationCommand(
        ClientFoundation clientFoundation,
        ClientVerificationContextCapture verificationContextCapture,
        VerificationEventLogger verificationLogger
    ) {
        if (clientFoundation == null
            || verificationContextCapture == null
            || verificationLogger == null) {
            throw new IllegalArgumentException("command dependencies must not be null");
        }
        this.clientFoundation = clientFoundation;
        this.verificationContextCapture = verificationContextCapture;
        this.verificationLogger = verificationLogger;
    }

    @Override
    public String getCommandName() {
        return "bedwarsbot";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/bedwarsbot <status|mode|input|marker>";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] arguments) throws CommandException {
        if (arguments.length == 0 || "status".equalsIgnoreCase(arguments[0])) {
            sendStatus(sender);
            return;
        }
        if ("mode".equalsIgnoreCase(arguments[0])) {
            handleMode(sender, arguments);
            return;
        }
        if ("input".equalsIgnoreCase(arguments[0])) {
            handleInput(sender, arguments);
            return;
        }
        if ("marker".equalsIgnoreCase(arguments[0])) {
            handleMarker(sender, arguments);
            return;
        }
        sendError(sender, getCommandUsage(sender));
    }

    private void handleMode(ICommandSender sender, String[] arguments) {
        if (arguments.length != 2) {
            sendError(sender, "/bedwarsbot mode <disabled|observe|shadow|assist|autonomous>");
            return;
        }

        try {
            BotMode mode = BotMode.valueOf(arguments[1].toUpperCase(Locale.ROOT));
            clientFoundation.setMode(mode, "client_command");
            sender.addChatMessage(new ChatComponentText("[Bedwars Bot] Mode: " + mode));
        } catch (IllegalArgumentException invalidMode) {
            sendError(sender, "Unknown mode: " + arguments[1]);
        }
    }

    private void handleInput(ICommandSender sender, String[] arguments) {
        if (arguments.length < 2) {
            sendInputUsage(sender);
            return;
        }

        String inputName = arguments[1].toLowerCase(Locale.ROOT);
        InputFrame frame;
        if ("clear".equals(inputName) || "none".equals(inputName)) {
            clientFoundation.clearProposedFrame("client_command");
            sender.addChatMessage(new ChatComponentText("[Bedwars Bot] Proposed input cleared."));
            return;
        } else if ("forward".equals(inputName)) {
            frame = InputFrame.builder().forward(true).build();
        } else if ("backward".equals(inputName)) {
            frame = InputFrame.builder().backward(true).build();
        } else if ("left".equals(inputName)) {
            frame = InputFrame.builder().left(true).build();
        } else if ("right".equals(inputName)) {
            frame = InputFrame.builder().right(true).build();
        } else if ("jump".equals(inputName)) {
            frame = InputFrame.builder().jump(true).build();
        } else if ("sneak".equals(inputName)) {
            frame = InputFrame.builder().sneak(true).build();
        } else if ("sprint".equals(inputName)) {
            frame = InputFrame.builder().sprint(true).build();
        } else if ("hotbar".equals(inputName)) {
            frame = parseHotbarFrame(sender, arguments);
            if (frame == null) {
                return;
            }
        } else {
            sendInputUsage(sender);
            return;
        }

        clientFoundation.setProposedFrame(frame, "client_command");
        sender.addChatMessage(new ChatComponentText(
            "[Bedwars Bot] Proposed input: " + frame.toCompactString()
        ));
    }

    private InputFrame parseHotbarFrame(ICommandSender sender, String[] arguments) {
        if (arguments.length != 3) {
            sendError(sender, "/bedwarsbot input hotbar <1-9>");
            return null;
        }
        try {
            int userSlot = Integer.parseInt(arguments[2]);
            if (userSlot < 1 || userSlot > 9) {
                throw new NumberFormatException("slot out of range");
            }
            return InputFrame.builder().hotbarSlot(userSlot - 1).build();
        } catch (NumberFormatException invalidSlot) {
            sendError(sender, "Hotbar slot must be between 1 and 9.");
            return null;
        }
    }

    private void sendStatus(ICommandSender sender) {
        sender.addChatMessage(new ChatComponentText(
            "[Bedwars Bot] mode=" + clientFoundation.getMode()
                + " proposed=" + clientFoundation.getProposedFrame().toCompactString()
                + " active=" + clientFoundation.getActiveFrame().toCompactString()
        ));
    }

    private void handleMarker(ICommandSender sender, String[] arguments) {
        if (arguments.length < 2) {
            sendError(sender, "/bedwarsbot marker <label>");
            return;
        }
        StringBuilder labelBuilder = new StringBuilder();
        for (int index = 1; index < arguments.length; index++) {
            if (labelBuilder.length() > 0) {
                labelBuilder.append(' ');
            }
            labelBuilder.append(arguments[index]);
        }
        String label = labelBuilder.toString().trim();
        if (label.isEmpty() || label.length() > VerificationEventLogger.MAX_LABEL_LENGTH) {
            sendError(
                sender,
                "Marker label must contain 1-" + VerificationEventLogger.MAX_LABEL_LENGTH
                    + " characters."
            );
            return;
        }

        try {
            boolean logged = verificationLogger.logMarker(
                label,
                clientFoundation.getClientTick(),
                clientFoundation.getCurrentWorldTick(),
                clientFoundation.getMode(),
                clientFoundation.getProposedFrame(),
                clientFoundation.getActiveFrame(),
                verificationContextCapture.capture()
            );
            if (logged) {
                sender.addChatMessage(new ChatComponentText(
                    "[Bedwars Bot] Verification marker logged: " + label
                ));
            } else {
                sendError(sender, "Verification marker could not be queued for logging.");
            }
        } catch (RuntimeException markerFailure) {
            String message = markerFailure.getMessage();
            sendError(
                sender,
                "Verification marker failed: "
                    + (message == null ? markerFailure.getClass().getSimpleName() : message)
            );
        }
    }

    private static void sendInputUsage(ICommandSender sender) {
        sendError(
            sender,
            "/bedwarsbot input <clear|forward|backward|left|right|jump|sneak|sprint|hotbar 1-9>"
        );
    }

    private static void sendError(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText(
            EnumChatFormatting.RED + "[Bedwars Bot] " + message
        ));
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }
}
