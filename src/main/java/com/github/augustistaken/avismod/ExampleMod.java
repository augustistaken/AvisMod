package com.github.augustistaken.avismod;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.util.*;

@Mod(modid = "avismod", useMetadata = true)
public class ExampleMod {

    private final KeyBinding scanKey = new KeyBinding("Scan for blocks", Keyboard.KEY_V, "AvisMod");
    private final KeyBinding stopKey = new KeyBinding("Stop Smooth Aim", Keyboard.KEY_B, "AvisMod");

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientRegistry.registerKeyBinding(scanKey);
        ClientRegistry.registerKeyBinding(stopKey);

        Events scanner = new Events(scanKey);
        MinecraftForge.EVENT_BUS.register(scanner);
        SmoothLookController lookController = new SmoothLookController(scanKey, stopKey, scanner);

        MinecraftForge.EVENT_BUS.register(lookController);
    }
}

class Events {
    private static final int CHUNK_RADIUS = 2;
    private static final int VERTICAL_RADIUS = 16;
    private static final int MAX_RESULTS = 20;
    private static final Block TARGET_BLOCK = Blocks.dirt;

    EntityPlayer player;
    KeyBinding scanKey;

    private static List<BlockPos> highlightedBlocks = new ArrayList<>();
    private static List<BlockPos> pathToFirstBlock = new ArrayList<>();

    Events(KeyBinding scanKey) {
        this.scanKey = scanKey;
    }


    public BlockPos getNextTarget() {
        if (highlightedBlocks.isEmpty()) return null;
        return highlightedBlocks.get(0);
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        player = event.player;
        player.addChatMessage(new ChatComponentText("Hello from AvisMod!"));
    }

    public static List<BlockPos> findNearestBlocks(EntityPlayer player, Block targetBlock,
                                                   int chunkRadius, int verticalRadius, int maxResults) {
        World world = player.worldObj;
        BlockPos playerPos = player.getPosition();
        int px = playerPos.getX();
        int py = playerPos.getY();
        int pz = playerPos.getZ();

        int chunkX = px >> 4;
        int chunkZ = pz >> 4;

        List<BlockPos> found = new ArrayList<>();

        for (int cx = chunkX - chunkRadius; cx <= chunkX + chunkRadius; cx++) {
            for (int cz = chunkZ - chunkRadius; cz <= chunkZ + chunkRadius; cz++) {
                Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
                int startX = cx << 4;
                int startZ = cz << 4;

                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int worldX = startX + x;
                        int worldZ = startZ + z;

                        for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                            int worldY = py + dy;
                            if (worldY < 0 || worldY >= world.getActualHeight()) continue;

                            BlockPos pos = new BlockPos(worldX, worldY, worldZ);
                            Block block = world.getBlockState(pos).getBlock();

                            if (block == targetBlock) {
                                found.add(pos);
                            }
                        }
                    }
                }
            }
        }

        found.sort(Comparator.comparingDouble(p -> p.distanceSq(px, py, pz)));

        if (found.size() > maxResults) {
            found = found.subList(0, maxResults);
        }

        highlightedBlocks = found;
        return found;
    }

    // --- Simple A* Pathfinding to the first block ---
    public static List<BlockPos> findPath(World world, BlockPos start, BlockPos goal, int radius) {
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost()));
        Map<BlockPos, Double> gScore = new HashMap<>();

        open.add(new Node(start, 0, start.distanceSq(goal), null));
        gScore.put(start, 0.0);

        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};

        while (!open.isEmpty()) {
            Node current = open.poll();
            if (current.pos.equals(goal)) return reconstruct(current);

            for (int[] d : dirs) {
                BlockPos next = current.pos.add(d[0], d[1], d[2]);
                if (start.distanceSq(next) > radius * radius) continue;
                Block block = world.getBlockState(next).getBlock();

                double step = block.isAir(world, next) ? 1 : 5;
                if (block == Blocks.bedrock) continue;

                double newG = gScore.get(current.pos) + step;
                if (!gScore.containsKey(next) || newG < gScore.get(next)) {
                    gScore.put(next, newG);
                    double h = next.distanceSq(goal);
                    open.add(new Node(next, newG, h, current));
                }
            }
        }

        return Collections.emptyList();
    }

    private static List<BlockPos> reconstruct(Node end) {
        List<BlockPos> path = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) {
            path.add(n.pos);
        }
        Collections.reverse(path);
        return path;
    }

    private static class Node {
        BlockPos pos;
        double g, h;
        Node parent;

        Node(BlockPos pos, double g, double h, Node parent) {
            this.pos = pos;
            this.g = g;
            this.h = h;
            this.parent = parent;
        }

        double fCost() {
            return g + h;
        }
    }

    // --- Rendering both red boxes and green path ---
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (player == null) return;

        double px = player.lastTickPosX + (player.posX - player.lastTickPosX) * event.partialTicks;
        double py = player.lastTickPosY + (player.posY - player.lastTickPosY) * event.partialTicks;
        double pz = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * event.partialTicks;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GL11.glLineWidth(2.0F);

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();

        // Draw red boxes for target blocks
        GlStateManager.color(1.0F, 0.0F, 0.0F, 0.6F);
        for (BlockPos pos : highlightedBlocks) {
            drawBox(wr, tess, pos, px, py, pz, 1.0);
        }

        // Draw green dots for path
        GlStateManager.color(0.0F, 1.0F, 0.0F, 0.9F);
        for (BlockPos pos : pathToFirstBlock) {
            drawBox(wr, tess, pos, px, py, pz, 0.2);
        }

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private void drawBox(WorldRenderer wr, Tessellator tess, BlockPos pos, double px, double py, double pz, double scale) {
        double x = pos.getX() - px + (1 - scale) / 2;
        double y = pos.getY() - py + (1 - scale) / 2;
        double z = pos.getZ() - pz + (1 - scale) / 2;

        AxisAlignedBB box = new AxisAlignedBB(x, y, z, x + scale, y + scale, z + scale);
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);

        wr.pos(box.minX, box.minY, box.minZ).endVertex();
        wr.pos(box.maxX, box.minY, box.minZ).endVertex();
        wr.pos(box.maxX, box.minY, box.minZ).endVertex();
        wr.pos(box.maxX, box.minY, box.maxZ).endVertex();
        wr.pos(box.maxX, box.minY, box.maxZ).endVertex();
        wr.pos(box.minX, box.minY, box.maxZ).endVertex();
        wr.pos(box.minX, box.minY, box.maxZ).endVertex();
        wr.pos(box.minX, box.minY, box.minZ).endVertex();

        wr.pos(box.minX, box.maxY, box.minZ).endVertex();
        wr.pos(box.maxX, box.maxY, box.minZ).endVertex();
        wr.pos(box.maxX, box.maxY, box.minZ).endVertex();
        wr.pos(box.maxX, box.maxY, box.maxZ).endVertex();
        wr.pos(box.maxX, box.maxY, box.maxZ).endVertex();
        wr.pos(box.minX, box.maxY, box.maxZ).endVertex();
        wr.pos(box.minX, box.maxY, box.maxZ).endVertex();
        wr.pos(box.minX, box.maxY, box.minZ).endVertex();

        wr.pos(box.minX, box.minY, box.minZ).endVertex();
        wr.pos(box.minX, box.maxY, box.minZ).endVertex();
        wr.pos(box.maxX, box.minY, box.minZ).endVertex();
        wr.pos(box.maxX, box.maxY, box.minZ).endVertex();
        wr.pos(box.maxX, box.minY, box.maxZ).endVertex();
        wr.pos(box.maxX, box.maxY, box.maxZ).endVertex();
        wr.pos(box.minX, box.minY, box.maxZ).endVertex();
        wr.pos(box.minX, box.maxY, box.maxZ).endVertex();

        tess.draw();
    }
}