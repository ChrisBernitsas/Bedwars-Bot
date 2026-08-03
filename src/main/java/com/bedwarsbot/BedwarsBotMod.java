package com.bedwarsbot;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.ClientCommandHandler;
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
    public static final String VERSION = "0.1.0";

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        ClientCommandHandler.instance.registerCommand(new SmokeTestCommand());
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
