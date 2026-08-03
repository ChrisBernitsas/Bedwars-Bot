package com.bedwarsbot.control;

import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

public final class InputController implements InputSink {
    private final GameSettings gameSettings;

    private boolean forwardOwned;
    private boolean backwardOwned;
    private boolean leftOwned;
    private boolean rightOwned;
    private boolean jumpOwned;
    private boolean sneakOwned;
    private boolean sprintOwned;
    private int lastHotbarSlot = InputFrame.NO_HOTBAR_SELECTION;
    private InputFrame activeFrame = InputFrame.neutral();

    public InputController(GameSettings gameSettings) {
        if (gameSettings == null) {
            throw new IllegalArgumentException("gameSettings must not be null");
        }
        this.gameSettings = gameSettings;
    }

    @Override
    public void apply(InputFrame permittedFrame) {
        if (permittedFrame == null) {
            throw new IllegalArgumentException("permittedFrame must not be null");
        }
        int hotbarSlot = permittedFrame.getHotbarSlot();
        if (hotbarSlot < InputFrame.NO_HOTBAR_SELECTION || hotbarSlot > 8) {
            throw new IllegalArgumentException("permittedFrame contains an invalid hotbar slot");
        }

        forwardOwned = updateBinding(gameSettings.keyBindForward, forwardOwned, permittedFrame.isForward());
        backwardOwned = updateBinding(gameSettings.keyBindBack, backwardOwned, permittedFrame.isBackward());
        leftOwned = updateBinding(gameSettings.keyBindLeft, leftOwned, permittedFrame.isLeft());
        rightOwned = updateBinding(gameSettings.keyBindRight, rightOwned, permittedFrame.isRight());
        jumpOwned = updateBinding(gameSettings.keyBindJump, jumpOwned, permittedFrame.isJump());
        sneakOwned = updateBinding(gameSettings.keyBindSneak, sneakOwned, permittedFrame.isSneak());
        sprintOwned = updateBinding(gameSettings.keyBindSprint, sprintOwned, permittedFrame.isSprint());

        if (hotbarSlot == InputFrame.NO_HOTBAR_SELECTION) {
            lastHotbarSlot = InputFrame.NO_HOTBAR_SELECTION;
        } else if (hotbarSlot != lastHotbarSlot) {
            KeyBinding hotbarBinding = gameSettings.keyBindsHotbar[hotbarSlot];
            if (hotbarBinding.getKeyCode() != 0) {
                KeyBinding.onTick(hotbarBinding.getKeyCode());
                lastHotbarSlot = hotbarSlot;
            } else {
                lastHotbarSlot = InputFrame.NO_HOTBAR_SELECTION;
            }
        }

        activeFrame = InputFrame.builder()
            .forward(forwardOwned)
            .backward(backwardOwned)
            .left(leftOwned)
            .right(rightOwned)
            .jump(jumpOwned)
            .sneak(sneakOwned)
            .sprint(sprintOwned)
            .hotbarSlot(lastHotbarSlot)
            .build();
    }

    @Override
    public void releaseAll() {
        forwardOwned = updateBinding(gameSettings.keyBindForward, forwardOwned, false);
        backwardOwned = updateBinding(gameSettings.keyBindBack, backwardOwned, false);
        leftOwned = updateBinding(gameSettings.keyBindLeft, leftOwned, false);
        rightOwned = updateBinding(gameSettings.keyBindRight, rightOwned, false);
        jumpOwned = updateBinding(gameSettings.keyBindJump, jumpOwned, false);
        sneakOwned = updateBinding(gameSettings.keyBindSneak, sneakOwned, false);
        sprintOwned = updateBinding(gameSettings.keyBindSprint, sprintOwned, false);
        lastHotbarSlot = InputFrame.NO_HOTBAR_SELECTION;
        activeFrame = InputFrame.neutral();
    }

    @Override
    public InputFrame getActiveFrame() {
        return activeFrame;
    }

    private static boolean updateBinding(KeyBinding binding, boolean owned, boolean requested) {
        if (binding.getKeyCode() == 0) {
            return false;
        }
        if (owned != requested) {
            KeyBinding.setKeyBindState(binding.getKeyCode(), requested);
        }
        return requested;
    }
}
