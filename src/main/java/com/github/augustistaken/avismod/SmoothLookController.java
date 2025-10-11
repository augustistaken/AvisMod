package com.github.augustistaken.avismod;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraft.world.World;
import net.minecraft.client.settings.KeyBinding;

import java.util.Random;

public class SmoothLookController {
    private static final float SMOOTHING = 0.15F;
    private static final float RANDOM_SHAKE = 0.4F;
    private static final float MAX_SPEED = 6.0F;
    private static final float MIN_DELTA = 0.05F;

    private final KeyBinding startKey;
    private final KeyBinding stopKey;
    private final Events scanner;
    private static boolean active = false;

    private static BlockPos targetBlock = null;
    private static final Random rand = new Random();

    public SmoothLookController(KeyBinding startKey, KeyBinding stopKey, Events scanner) {
        this.startKey = startKey;
        this.stopKey = stopKey;
        this.scanner = scanner;
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (player == null) return;

        // START AI on V
        if (startKey.isPressed()) {
            // Scan first
            scanner.findNearestBlocks(player, Blocks.dirt, 2, 16, 20);
            // or scanner.scanForBlocks(player); depending on your naming
            targetBlock = scanner.getNextTarget();

            if (targetBlock != null) {
                player.addChatMessage(new ChatComponentText("§aTargeting block at " + targetBlock));
                active = true;
            } else {
                player.addChatMessage(new ChatComponentText("§cNo target blocks found."));
            }
        }

        // STOP AI on B
        if (stopKey.isPressed()) {
            active = false;
            targetBlock = null;
            player.addChatMessage(new ChatComponentText("§cAI stopped."));
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (!active) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        World world = mc.theWorld;

        if (player == null || world == null) return;

        if (targetBlock == null || world.getBlockState(targetBlock).getBlock().isAir(world, targetBlock)) {
            targetBlock = scanner.getNextTarget();
            if (targetBlock == null) {
                player.addChatMessage(new ChatComponentText("§eNo more blocks."));
                active = false;
                return;
            }
        }

        // Compute direction
        double dx = targetBlock.getX() + 0.5 - player.posX;
        double dy = targetBlock.getY() + 0.5 - (player.posY + player.getEyeHeight());
        double dz = targetBlock.getZ() + 0.5 - player.posZ;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        double distTotal = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // Pitch offset for "look up, then aim down" behavior
        float pitchOffset = (float) MathHelper.clamp_double((distTotal / 15.0), 0.2, 1.0);
        pitchOffset *= -12.0F;

        float targetYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float targetPitch = (float) (-(MathHelper.atan2(dy, distXZ) * (180.0 / Math.PI))) + pitchOffset;

        targetYaw += (rand.nextFloat() - 0.5F) * RANDOM_SHAKE;
        targetPitch += (rand.nextFloat() - 0.5F) * RANDOM_SHAKE;

        float yawDiff = wrapDegrees(targetYaw - player.rotationYaw);
        float pitchDiff = targetPitch - player.rotationPitch;

        yawDiff = MathHelper.clamp_float(yawDiff, -MAX_SPEED, MAX_SPEED);
        pitchDiff = MathHelper.clamp_float(pitchDiff, -MAX_SPEED, MAX_SPEED);

        player.rotationYaw += yawDiff * SMOOTHING;
        player.rotationPitch += pitchDiff * SMOOTHING;

        if (distTotal < 2.0) {
            player.addChatMessage(new ChatComponentText("§bReached block " + targetBlock));
            targetBlock = scanner.getNextTarget();
        }
    }

    private float wrapDegrees(float val) {
        val = val % 360F;
        if (val >= 180F) val -= 360F;
        if (val < -180F) val += 360F;
        return val;
    }
}