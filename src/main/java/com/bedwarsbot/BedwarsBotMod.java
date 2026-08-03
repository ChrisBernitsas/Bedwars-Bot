package com.bedwarsbot;

import java.io.File;

import com.bedwarsbot.command.ClientFoundationCommand;
import com.bedwarsbot.control.ActionSafetyGate;
import com.bedwarsbot.control.BotModeStateMachine;
import com.bedwarsbot.control.ClientFoundation;
import com.bedwarsbot.control.InputController;
import com.bedwarsbot.control.ManualOverride;
import com.bedwarsbot.hud.DebugHud;
import com.bedwarsbot.logging.AsyncSessionLogger;
import com.bedwarsbot.observation.ClientBlockObservationHooks;
import com.bedwarsbot.observation.ObservationPipeline;
import com.bedwarsbot.verification.VerificationEventLogger;
import com.bedwarsbot.verification.ClientVerificationContextCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

@Mod(
    modid = BedwarsBotMod.MOD_ID,
    name = BedwarsBotMod.MOD_NAME,
    version = BedwarsBotMod.VERSION,
    acceptedMinecraftVersions = "[1.8.9]",
    acceptableRemoteVersions = "*",
    clientSideOnly = true
)
public final class BedwarsBotMod {
    public static final String MOD_ID = "bedwarsbot";
    public static final String MOD_NAME = "Bedwars Bot";
    public static final String VERSION = "0.3.0";

    private AsyncSessionLogger sessionLogger;
    private ObservationPipeline observationPipeline;

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        sessionLogger = new AsyncSessionLogger(
            new File(minecraft.mcDataDir, "bedwarsbot/logs").toPath()
        );
        observationPipeline = new ObservationPipeline(sessionLogger);
        final VerificationEventLogger verificationLogger = new VerificationEventLogger(
            sessionLogger
        );
        ClientVerificationContextCapture verificationContextCapture =
            new ClientVerificationContextCapture(minecraft);
        ClientBlockObservationHooks observationHooks = new ClientBlockObservationHooks(
            minecraft,
            observationPipeline
        );
        ClientFoundation clientFoundation = new ClientFoundation(
            minecraft,
            new BotModeStateMachine(),
            new ActionSafetyGate(),
            new InputController(minecraft.gameSettings),
            sessionLogger,
            VERSION
        );
        ManualOverride manualOverride = new ManualOverride(clientFoundation);

        FMLCommonHandler.instance().bus().register(clientFoundation);
        FMLCommonHandler.instance().bus().register(manualOverride);
        FMLCommonHandler.instance().bus().register(observationHooks);
        MinecraftForge.EVENT_BUS.register(observationHooks);
        MinecraftForge.EVENT_BUS.register(new DebugHud(
            clientFoundation.getHudSnapshotReference(),
            observationPipeline.getHudSnapshotReference()
        ));

        ClientCommandHandler.instance.registerCommand(new SmokeTestCommand());
        ClientCommandHandler.instance.registerCommand(new ClientFoundationCommand(
            clientFoundation,
            verificationContextCapture,
            verificationLogger
        ));

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    observationPipeline.close();
                    verificationLogger.logObservationPipelineSummary(
                        observationPipeline.getHudSnapshotReference().get()
                    );
                } finally {
                    sessionLogger.close();
                }
            }
        }, "bedwarsbot-log-shutdown"));
    }

    private static final class SmokeTestCommand extends CommandBase {
        @Override
        public String getCommandName() {
            return "bedwarsbotsmoke";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/bedwarsbotsmoke";
        }

        @Override
        public void processCommand(ICommandSender sender, String[] arguments) throws CommandException {
            sender.addChatMessage(new ChatComponentText("[Bedwars Bot] Phase 0 loaded."));
        }

        @Override
        public boolean canCommandSenderUseCommand(ICommandSender sender) {
            return true;
        }
    }
}
