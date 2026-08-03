package com.bedwarsbot.control;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class ManualOverride {
    public static final int DEFAULT_KEY_CODE = Keyboard.KEY_F10;

    private final ClientFoundation clientFoundation;
    private final KeyBinding overrideKey;

    private boolean previouslyDown;

    public ManualOverride(ClientFoundation clientFoundation) {
        if (clientFoundation == null) {
            throw new IllegalArgumentException("clientFoundation must not be null");
        }
        this.clientFoundation = clientFoundation;
        this.overrideKey = new KeyBinding(
            "key.bedwarsbot.manual_override",
            DEFAULT_KEY_CODE,
            "key.categories.bedwarsbot"
        );
        ClientRegistry.registerKeyBinding(overrideKey);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        pollOverrideKey();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            pollOverrideKey();
        }
    }

    private void pollOverrideKey() {
        boolean down = overrideKey.isKeyDown() || isPhysicalKeyDown(overrideKey.getKeyCode());
        if (down && !previouslyDown) {
            clientFoundation.disableImmediately("manual_override_key");
        }
        previouslyDown = down;
    }

    private static boolean isPhysicalKeyDown(int keyCode) {
        if (keyCode >= 0) {
            return Keyboard.isCreated() && Keyboard.isKeyDown(keyCode);
        }
        int mouseButton = keyCode + 100;
        return mouseButton >= 0 && Mouse.isCreated() && Mouse.isButtonDown(mouseButton);
    }
}
