package com.bedwarsbot.verification;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

public final class ClientVerificationContextCapture {
    private final Minecraft minecraft;

    public ClientVerificationContextCapture(Minecraft minecraft) {
        if (minecraft == null) {
            throw new IllegalArgumentException("minecraft must not be null");
        }
        this.minecraft = minecraft;
    }

    public VerificationMarkerContext capture() {
        if (!minecraft.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("verification context must be captured on the client thread");
        }

        Map<String, String> details = new LinkedHashMap<String, String>();
        capturePlayer(details);
        captureCrosshair(details);
        return new VerificationMarkerContext(details);
    }

    private void capturePlayer(Map<String, String> details) {
        if (minecraft.thePlayer == null) {
            details.put("player_available", "false");
            return;
        }

        details.put("player_available", "true");
        details.put("player_dimension", Integer.toString(minecraft.thePlayer.dimension));
        details.put("player_block_x", Integer.toString(MathHelper.floor_double(minecraft.thePlayer.posX)));
        details.put("player_block_y", Integer.toString(MathHelper.floor_double(minecraft.thePlayer.posY)));
        details.put("player_block_z", Integer.toString(MathHelper.floor_double(minecraft.thePlayer.posZ)));
        details.put("player_x", Double.toString(minecraft.thePlayer.posX));
        details.put("player_y", Double.toString(minecraft.thePlayer.posY));
        details.put("player_z", Double.toString(minecraft.thePlayer.posZ));
        details.put("player_yaw", Float.toString(minecraft.thePlayer.rotationYaw));
        details.put("player_pitch", Float.toString(minecraft.thePlayer.rotationPitch));

        ItemStack heldStack = minecraft.thePlayer.getHeldItem();
        if (heldStack == null || heldStack.getItem() == null) {
            details.put("held_item_available", "false");
            return;
        }
        Item heldItem = heldStack.getItem();
        Object registryName = Item.itemRegistry.getNameForObject(heldItem);
        details.put("held_item_available", "true");
        details.put(
            "held_item_registry_name",
            registryName == null ? "unregistered:item" : registryName.toString()
        );
        details.put("held_item_metadata", Integer.toString(heldStack.getMetadata()));
    }

    private void captureCrosshair(Map<String, String> details) {
        MovingObjectPosition target = minecraft.objectMouseOver;
        if (target == null || target.typeOfHit == null) {
            details.put("crosshair_target_type", "NONE");
            return;
        }
        details.put("crosshair_target_type", target.typeOfHit.name());
        if (target.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }

        BlockPos position = target.getBlockPos();
        if (position == null) {
            details.put("target_block_available", "false");
            return;
        }
        int dimension = minecraft.thePlayer != null
            ? minecraft.thePlayer.dimension
            : minecraft.theWorld == null ? 0 : minecraft.theWorld.provider.getDimensionId();
        details.put("target_block_dimension", Integer.toString(dimension));
        details.put("target_block_x", Integer.toString(position.getX()));
        details.put("target_block_y", Integer.toString(position.getY()));
        details.put("target_block_z", Integer.toString(position.getZ()));
        if (minecraft.theWorld == null || !minecraft.theWorld.isBlockLoaded(position)) {
            details.put("target_block_available", "false");
            return;
        }

        IBlockState minecraftState = minecraft.theWorld.getBlockState(position);
        Block block = minecraftState.getBlock();
        Object registryName = Block.blockRegistry.getNameForObject(block);
        details.put("target_block_available", "true");
        details.put("target_block_id", Integer.toString(Block.getIdFromBlock(block)));
        details.put("target_block_metadata", Integer.toString(block.getMetaFromState(minecraftState)));
        details.put(
            "target_block_registry_name",
            registryName == null ? "unregistered:block" : registryName.toString()
        );
    }
}
