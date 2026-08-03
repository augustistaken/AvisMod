package com.github.augustistaken.avismod;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemHoe;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class CanePathMapper {
    private static final int SCAN_RADIUS = 3 * 16;
    private static final int COLUMNS_PER_TICK = 384;
    private static final int MAX_PATH_NODES = 24000;
    private static final int STEERING_LOOKAHEAD_NODES = 6;
    private static final int PATH_CATCHUP_WINDOW = 8;
    private static final int TURN_PREPARE_NODES = 3;
    private static final int SIDE_HOLD_TICKS = 2;
    private static final int MIN_STAGING_HOLD_TICKS = 5;
    private static final int MAX_STAGING_HOLD_TICKS = 10;
    private static final int REQUIRED_ENTRY_SIDE_SWEEPS = 2;
    private static final double STAGING_DISTANCE = 1.0;
    private static final double SIDE_AIM_OUTSET = 0.16;
    private static final int REGROW_CHECK_INTERVAL_TICKS = 40;
    private static final double REGROW_READY_RATIO = 0.95;
    private static final double BREAK_REACH = 4.45;
    private static final double NODE_REACHED_DISTANCE = 0.42;
    private static final int[][] PATH_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final int[][] RETURN_OFFSETS = {
            {1, 0}, {0, 1}, {-1, 0}, {0, -1},
            {1, 1}, {-1, 1}, {-1, -1}, {1, -1},
            {2, 0}, {0, 2}, {-2, 0}, {0, -2}
    };
    private static boolean movementOverrideActive;
    private static float overrideForward;
    private static float overrideStrafe;
    private static boolean overrideJump;

    private final KeyBinding mapKey;
    private final Set<BlockPos> markedCane = new HashSet<>();
    private final Set<BlockPos> caneBases = new HashSet<>();
    private final Set<BlockPos> mappedCanePositions = new HashSet<>();
    private final List<RoutePoint> travelPath = new ArrayList<>();

    private boolean active;
    private boolean scanning;
    private boolean waitingForRegrowth;
    private boolean movementOwned;
    private boolean completionAnnounced;
    private World mappedWorld;
    private BlockPos scanOrigin;
    private RoutePoint homePoint;
    private RoutePoint activeReturnPoint;
    private RoutePoint firstAisleMiddlePoint;
    private BlockPos homeBlock;
    private BlockPos lastReturnBlock;
    private int returnVariantCursor;
    private int regrowCheckTicks;
    private int scanX;
    private int scanZ;
    private int pathIndex;
    private int jumpHoldTicks;
    private int stagingHoldTicks;
    private int stagingSideSweeps;
    private BlockPos breakTarget;
    private boolean targetLeftSide = true;
    private int harvestAisleId = -1;
    private int centeredEntryTicks;
    private int breakDelay;
    private int breakAttemptTicks;
    private boolean attackOwned;
    private boolean pauseSettingCaptured;
    private boolean previousPauseOnLostFocus;
    private float yawVelocity;
    private float pitchVelocity;
    private final double cameraNoiseSeed = Math.random() * Math.PI * 2.0;
    private double aimOffsetX;
    private double aimOffsetY;
    private double aimOffsetZ;

    public CanePathMapper(KeyBinding mapKey) {
        this.mapKey = mapKey;
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (!mapKey.isPressed()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (active) {
            stopAndClear();
            mc.ingameGUI.getChatGUI().clearChatMessages();
            return;
        }

        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (!isHoldingHoe(mc.thePlayer)) {
            mc.thePlayer.addChatMessage(new ChatComponentText(
                    "\u00A7cSelect a hoe before starting cane automation."));
            return;
        }
        previousPauseOnLostFocus = mc.gameSettings.pauseOnLostFocus;
        pauseSettingCaptured = true;
        mc.gameSettings.pauseOnLostFocus = false;
        active = true;
        scanning = true;
        completionAnnounced = false;
        mappedWorld = mc.theWorld;
        scanOrigin = mc.thePlayer.getPosition();
        homePoint = new RoutePoint(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        homeBlock = mc.thePlayer.getPosition();
        activeReturnPoint = null;
        firstAisleMiddlePoint = null;
        lastReturnBlock = null;
        returnVariantCursor = 0;
        waitingForRegrowth = false;
        regrowCheckTicks = 0;
        scanX = -SCAN_RADIUS;
        scanZ = -SCAN_RADIUS;
        pathIndex = 0;
        markedCane.clear();
        caneBases.clear();
        mappedCanePositions.clear();
        travelPath.clear();
        releaseMovement();
        mc.thePlayer.addChatMessage(new ChatComponentText(
                "\u00A7bMapping cane within three chunks..."));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!active) return;

        Minecraft mc = Minecraft.getMinecraft();
        mc.gameSettings.pauseOnLostFocus = false;
        if (mc.theWorld == null || mc.thePlayer == null || mc.theWorld != mappedWorld) {
            stopAndClear();
            return;
        }
        if (mc.thePlayer.hurtTime > 0) {
            EntityPlayer player = mc.thePlayer;
            stopAndClear();
            player.addChatMessage(new ChatComponentText(
                    "\u00A7cCane automation stopped because you were hit."));
            return;
        }
        if (!isHoldingHoe(mc.thePlayer)) {
            EntityPlayer player = mc.thePlayer;
            stopAndClear();
            player.addChatMessage(new ChatComponentText(
                    "\u00A7cCane automation stopped because the selected slot is not a hoe."));
            return;
        }

        if (event.phase == TickEvent.Phase.END) {
            if (!scanning && !waitingForRegrowth && mc.currentScreen == null && !travelPath.isEmpty()
                    && pathIndex < travelPath.size()) {
                updateHeadAndBreak(mc, mc.thePlayer, mc.theWorld,
                        chooseSteeringNode(mc.theWorld, mc.thePlayer));
            }
            return;
        }

        if (scanning) {
            processScan(mc.theWorld);
            return;
        }
        if (waitingForRegrowth) {
            processRegrowthWait(mc, mc.theWorld, mc.thePlayer);
            return;
        }
        if (mc.currentScreen != null || travelPath.isEmpty() || pathIndex >= travelPath.size()) {
            releaseMovementIfOwned();
            return;
        }

        followPath(mc, mc.thePlayer, mc.theWorld);
    }

    private void processScan(World world) {
        int processed = 0;
        int worldHeight = world.getActualHeight();

        while (scanning && processed++ < COLUMNS_PER_TICK) {
            if (scanX * scanX + scanZ * scanZ <= SCAN_RADIUS * SCAN_RADIUS) {
                int worldX = scanOrigin.getX() + scanX;
                int worldZ = scanOrigin.getZ() + scanZ;
                BlockPos column = new BlockPos(worldX, scanOrigin.getY(), worldZ);

                if (world.isBlockLoaded(column)) {
                    for (int y = 1; y < worldHeight; y++) {
                        BlockPos pos = new BlockPos(worldX, y, worldZ);
                        if (!isReed(world, pos)
                                || !isReed(world, pos.down())
                                || isReed(world, pos.down().down())) continue;

                        // Mark only the second block of each column, never the third block.
                        markedCane.add(pos);
                        mappedCanePositions.add(pos);
                        caneBases.add(pos.down());
                    }
                }
            }

            advanceScanColumn();
        }

        if (!scanning) finishMapping(world);
    }

    private void advanceScanColumn() {
        scanZ++;
        if (scanZ > SCAN_RADIUS) {
            scanZ = -SCAN_RADIUS;
            scanX++;
        }
        if (scanX > SCAN_RADIUS) scanning = false;
    }

    private void finishMapping(World world) {
        Minecraft mc = Minecraft.getMinecraft();
        List<Aisle> aisles = findCenterAisles(world);
        travelPath.addAll(buildSerpentineRoute(world, mc.thePlayer, aisles));
        pathIndex = travelPath.size() > 1 ? 1 : 0;

        mc.thePlayer.addChatMessage(new ChatComponentText(
                "\u00A7aMarked " + markedCane.size() + " upper cane blocks; found "
                        + aisles.size() + " center aisles; path has " + travelPath.size() + " nodes."));
        if (travelPath.isEmpty()) {
            mc.thePlayer.addChatMessage(new ChatComponentText(
                    "\u00A7eNo safe paired-row route was found."));
        }
    }

    private List<Aisle> findCenterAisles(World world) {
        if (caneBases.isEmpty()) return Collections.emptyList();

        Set<Long> columns = new HashSet<>();
        for (BlockPos base : caneBases) columns.add(columnKey(base.getX(), base.getZ()));

        int xConnections = 0;
        int zConnections = 0;
        for (BlockPos base : caneBases) {
            if (columns.contains(columnKey(base.getX() + 1, base.getZ()))
                    || columns.contains(columnKey(base.getX() - 1, base.getZ()))) xConnections++;
            if (columns.contains(columnKey(base.getX(), base.getZ() + 1))
                    || columns.contains(columnKey(base.getX(), base.getZ() - 1))) zConnections++;
        }
        boolean rowsAlongX = xConnections >= zConnections;

        Map<Integer, List<BlockPos>> rows = new TreeMap<>();
        for (BlockPos base : caneBases) {
            int coordinate = rowsAlongX ? base.getZ() : base.getX();
            rows.computeIfAbsent(coordinate, ignored -> new ArrayList<>()).add(base);
        }

        List<Aisle> aisles = new ArrayList<>();
        List<Integer> rowCoordinates = new ArrayList<>(rows.keySet());
        for (int i = 0; i < rowCoordinates.size() - 1; i++) {
            int firstCoordinate = rowCoordinates.get(i);
            int secondCoordinate = rowCoordinates.get(i + 1);
            if (secondCoordinate - firstCoordinate != 1) continue;
            List<BlockPos> firstRow = rows.get(firstCoordinate);
            List<BlockPos> secondRow = rows.get(secondCoordinate);

            Aisle aisle = createAisle(world, firstRow, secondRow, rowsAlongX,
                    (firstCoordinate + secondCoordinate + 1.0) / 2.0, aisles.size());
            if (aisle != null) {
                aisles.add(aisle);
                // A cane line belongs to one farm pair only; do not reuse it in another aisle.
                i++;
            }
        }
        return aisles;
    }

    private Aisle createAisle(World world, List<BlockPos> firstRow, List<BlockPos> secondRow,
                              boolean rowsAlongX, double laneCoordinate, int aisleId) {
        Map<Integer, BlockPos> first = indexRow(firstRow, rowsAlongX);
        Map<Integer, BlockPos> second = indexRow(secondRow, rowsAlongX);
        TreeSet<Integer> shared = new TreeSet<>(first.keySet());
        shared.retainAll(second.keySet());
        if (shared.size() < 2) return null;

        List<Integer> longestRun = longestContiguousRun(shared);
        if (longestRun.size() < 2) return null;

        List<RoutePoint> nodes = new ArrayList<>();
        for (int position : longestRun) {
            BlockPos firstBase = first.get(position);
            BlockPos secondBase = second.get(position);
            int referenceY = Math.min(firstBase.getY(), secondBase.getY());
            RoutePoint node = findLanePoint(world, rowsAlongX, position, laneCoordinate,
                    referenceY, aisleId);
            if (node == null) return null;
            nodes.add(node);
        }
        return new Aisle(nodes);
    }

    private Map<Integer, BlockPos> indexRow(List<BlockPos> row, boolean rowsAlongX) {
        Map<Integer, BlockPos> indexed = new HashMap<>();
        for (BlockPos base : row) {
            indexed.put(rowsAlongX ? base.getX() : base.getZ(), base);
        }
        return indexed;
    }

    private List<Integer> longestContiguousRun(TreeSet<Integer> positions) {
        List<Integer> best = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        Integer previous = null;

        for (int position : positions) {
            if (previous == null || position == previous + 1) {
                current.add(position);
            } else {
                if (current.size() > best.size()) best = new ArrayList<>(current);
                current.clear();
                current.add(position);
            }
            previous = position;
        }
        if (current.size() > best.size()) best = current;
        return best;
    }

    private RoutePoint findLanePoint(World world, boolean rowsAlongX, int position,
                                     double laneCoordinate, int referenceY, int aisleId) {
        for (int yOffset : new int[]{0, 1, -1, 2, -2}) {
            RoutePoint candidate = rowsAlongX
                    ? new RoutePoint(position + 0.5, referenceY + yOffset, laneCoordinate, aisleId)
                    : new RoutePoint(laneCoordinate, referenceY + yOffset, position + 0.5, aisleId);
            if (isPointNavigable(world, candidate)) return candidate;
        }
        return null;
    }

    private List<RoutePoint> buildSerpentineRoute(World world, EntityPlayer player, List<Aisle> aisles) {
        List<RoutePoint> route = new ArrayList<>();
        List<Aisle> remaining = new ArrayList<>(aisles);
        activeReturnPoint = null;
        firstAisleMiddlePoint = null;
        RoutePoint current = new RoutePoint(player.posX, player.posY, player.posZ);
        BlockPos currentBlock = player.getPosition();
        boolean visitedAisle = false;
        boolean hasRouteHeading = false;
        double routeHeadingX = 0.0;
        double routeHeadingZ = 0.0;
        route.add(current);

        while (!remaining.isEmpty()) {
            Aisle selected = null;
            boolean reverse = false;
            double bestCost = Double.MAX_VALUE;
            List<BlockPos> bestConnector = Collections.emptyList();
            BlockPos bestEntryBlock = null;
            RoutePoint bestStagingPoint = null;

            for (Aisle aisle : remaining) {
                for (boolean candidateReverse : new boolean[]{false, true}) {
                    RoutePoint stagingPoint = createStagingPoint(world, aisle, candidateReverse);
                    if (stagingPoint == null) continue;
                    if (hasRouteHeading && isBehindCurrentHeading(
                            current, stagingPoint, routeHeadingX, routeHeadingZ)) continue;
                    BlockPos endpointBlock = nearestNavigableBlock(world, stagingPoint, currentBlock);
                    if (endpointBlock == null) continue;
                    if (!hasSafeDirectLine(
                            world, routePoint(endpointBlock), stagingPoint)) continue;
                    List<BlockPos> candidateConnector = findSafePath(world, currentBlock, endpointBlock);
                    if (candidateConnector.isEmpty()) continue;
                    if (hasRouteHeading && connectorStartsInReverse(
                            candidateConnector, routeHeadingX, routeHeadingZ)) continue;

                    double cost = connectorCost(candidateConnector)
                            + stagingPoint.distanceSq(new RoutePoint(
                            endpointBlock.getX() + 0.5, endpointBlock.getY(), endpointBlock.getZ() + 0.5));
                    if (cost < bestCost) {
                        bestCost = cost;
                        selected = aisle;
                        reverse = candidateReverse;
                        bestConnector = candidateConnector;
                        bestEntryBlock = endpointBlock;
                        bestStagingPoint = stagingPoint;
                    }
                }
            }

            if (selected == null) break;

            List<RoutePoint> orderedAisle = new ArrayList<>(selected.nodes);
            if (reverse) Collections.reverse(orderedAisle);
            if (!visitedAisle) {
                firstAisleMiddlePoint = orderedAisle.get(orderedAisle.size() / 2);
            }

            remaining.remove(selected);
            appendConnector(world, route, bestConnector);
            appendRoutePoint(route, bestStagingPoint);
            appendRoutePoints(route, orderedAisle);
            visitedAisle = true;
            current = orderedAisle.get(orderedAisle.size() - 1);
            RoutePoint previous = orderedAisle.get(orderedAisle.size() - 2);
            double headingX = current.x - previous.x;
            double headingZ = current.z - previous.z;
            double headingLength = Math.sqrt(headingX * headingX + headingZ * headingZ);
            if (headingLength > 0.001) {
                routeHeadingX = headingX / headingLength;
                routeHeadingZ = headingZ / headingLength;
                hasRouteHeading = true;
            }
            currentBlock = nearestNavigableBlock(world, current, bestEntryBlock);
        }

        if (visitedAisle && homePoint != null && currentBlock != null) {
            ReturnDestination destination = chooseReturnDestination(world, currentBlock);
            if (destination != null) {
                activeReturnPoint = destination.point;
                appendConnector(world, route, destination.path);
                appendRoutePoint(route, destination.point);
            }
        }

        return route;
    }

    private ReturnDestination chooseReturnDestination(World world, BlockPos currentBlock) {
        if (homeBlock == null || homePoint == null) return null;

        for (int attempt = 0; attempt < RETURN_OFFSETS.length; attempt++) {
            int index = (returnVariantCursor + attempt) % RETURN_OFFSETS.length;
            int[] offset = RETURN_OFFSETS[index];
            BlockPos candidate = findSafeStep(world,
                    homeBlock.add(offset[0], 0, offset[1]));
            if (candidate == null || candidate.equals(lastReturnBlock)) continue;

            List<BlockPos> path = findSafePath(world, currentBlock, candidate);
            if (path.isEmpty()) continue;

            lastReturnBlock = candidate;
            returnVariantCursor = (index + 1) % RETURN_OFFSETS.length;
            return new ReturnDestination(routePoint(candidate), path);
        }

        if (lastReturnBlock != null && isNavigable(world, lastReturnBlock)) {
            List<BlockPos> repeatedPath = findSafePath(world, currentBlock, lastReturnBlock);
            if (!repeatedPath.isEmpty()) {
                return new ReturnDestination(routePoint(lastReturnBlock), repeatedPath);
            }
        }

        BlockPos fallback = nearestNavigableBlock(world, homePoint, homeBlock);
        if (fallback == null) return null;
        List<BlockPos> fallbackPath = findSafePath(world, currentBlock, fallback);
        if (fallbackPath.isEmpty()) return null;
        lastReturnBlock = fallback;
        return new ReturnDestination(homePoint, fallbackPath);
    }

    private boolean isBehindCurrentHeading(RoutePoint current, RoutePoint target,
                                           double headingX, double headingZ) {
        double forwardDisplacement = (target.x - current.x) * headingX
                + (target.z - current.z) * headingZ;
        return forwardDisplacement < -0.75;
    }

    private boolean connectorStartsInReverse(List<BlockPos> connector,
                                             double headingX, double headingZ) {
        if (connector.size() < 2) return false;
        BlockPos start = connector.get(0);
        for (int i = 1; i < connector.size() && i <= 3; i++) {
            BlockPos next = connector.get(i);
            double dx = next.getX() - start.getX();
            double dz = next.getZ() - start.getZ();
            double forward = dx * headingX + dz * headingZ;
            if (Math.abs(dx) + Math.abs(dz) > 0.0) return forward < -0.05;
        }
        return false;
    }

    private RoutePoint createStagingPoint(World world, Aisle aisle, boolean reverse) {
        RoutePoint entry = reverse ? aisle.last() : aisle.first();
        RoutePoint second = reverse
                ? aisle.nodes.get(aisle.nodes.size() - 2) : aisle.nodes.get(1);
        double dx = second.x - entry.x;
        double dz = second.z - entry.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) return null;
        RoutePoint staging = new RoutePoint(
                entry.x - dx / length * STAGING_DISTANCE,
                entry.y,
                entry.z - dz / length * STAGING_DISTANCE,
                entry.aisleId, true);
        if (isPointNavigable(world, staging)) return staging;
        return null;
    }

    private double connectorCost(List<BlockPos> connector) {
        double cost = 0.0;
        for (int i = 1; i < connector.size(); i++) {
            BlockPos previous = connector.get(i - 1);
            BlockPos current = connector.get(i);
            cost += 1.0 + Math.abs(current.getY() - previous.getY()) * 0.45;
        }
        return cost;
    }

    private BlockPos nearestNavigableBlock(World world, RoutePoint point, BlockPos reference) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int[] xValues = {(int) Math.floor(point.x - 0.31), (int) Math.floor(point.x + 0.31)};
        int[] zValues = {(int) Math.floor(point.z - 0.31), (int) Math.floor(point.z + 0.31)};

        for (int x : xValues) {
            for (int z : zValues) {
                for (int yOffset : new int[]{0, 1, -1}) {
                    BlockPos candidate = new BlockPos(x, MathHelper.floor_double(point.y) + yOffset, z);
                    if (!isNavigable(world, candidate)) continue;
                    double distance = candidate.distanceSq(reference);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private List<BlockPos> findSafePath(World world, BlockPos start, BlockPos goal) {
        if (start.equals(goal)) return Collections.singletonList(start);
        if (!isNavigable(world, goal)) return Collections.emptyList();

        PriorityQueue<PathNode> open = new PriorityQueue<>(
                Comparator.<PathNode>comparingDouble(node -> node.f)
                        .thenComparingDouble(node -> heuristic(node.pos, goal)));
        Map<BlockPos, Double> bestCost = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        open.add(new PathNode(start, 0.0, heuristic(start, goal), null));
        bestCost.put(start, 0.0);
        int visited = 0;

        while (!open.isEmpty() && visited++ < MAX_PATH_NODES) {
            PathNode current = open.poll();
            if (!closed.add(current.pos)) continue;
            if (current.pos.equals(goal)) return reconstruct(current);

            for (int[] direction : PATH_DIRECTIONS) {
                BlockPos horizontal = current.pos.add(direction[0], 0, direction[1]);
                BlockPos next = findSafeStep(world, horizontal);
                if (next == null || closed.contains(next)) continue;
                boolean diagonal = direction[0] != 0 && direction[1] != 0;
                if (diagonal && !isSafeDiagonalStep(
                        world, current.pos, next, direction[0], direction[1])) continue;
                if (Math.abs(next.getX() - start.getX()) > SCAN_RADIUS * 2
                        || Math.abs(next.getZ() - start.getZ()) > SCAN_RADIUS * 2
                        || Math.abs(next.getY() - start.getY()) > 32) continue;

                double stepCost = (diagonal ? Math.sqrt(2.0) : 1.0)
                        + Math.abs(next.getY() - current.pos.getY()) * 0.45;
                if (current.parent != null) {
                    int oldDx = current.pos.getX() - current.parent.pos.getX();
                    int oldDz = current.pos.getZ() - current.parent.pos.getZ();
                    if (oldDx != direction[0] || oldDz != direction[1]) stepCost += 0.22;
                }
                double newCost = current.g + stepCost;
                if (newCost >= bestCost.getOrDefault(next, Double.MAX_VALUE)) continue;

                bestCost.put(next, newCost);
                open.add(new PathNode(next, newCost, newCost + heuristic(next, goal), current));
            }
        }
        return Collections.emptyList();
    }

    private boolean isSafeDiagonalStep(World world, BlockPos current, BlockPos next,
                                       int dx, int dz) {
        BlockPos xClearance = findSafeStep(world, current.add(dx, 0, 0));
        BlockPos zClearance = findSafeStep(world, current.add(0, 0, dz));
        if (xClearance == null || zClearance == null) return false;
        if (Math.abs(xClearance.getY() - current.getY()) > 1
                || Math.abs(zClearance.getY() - current.getY()) > 1
                || Math.abs(xClearance.getY() - next.getY()) > 1
                || Math.abs(zClearance.getY() - next.getY()) > 1) return false;
        return hasSafeDirectLine(world, routePoint(current), routePoint(next));
    }

    private BlockPos findSafeStep(World world, BlockPos horizontal) {
        if (isNavigable(world, horizontal)) return horizontal;
        if (isNavigable(world, horizontal.up())) return horizontal.up();
        if (isNavigable(world, horizontal.down())) return horizontal.down();
        return null;
    }

    private boolean isNavigable(World world, BlockPos feet) {
        if (!world.isBlockLoaded(feet) || !isPassable(world, feet) || !isPassable(world, feet.up())) {
            return false;
        }
        Material feetMaterial = materialAt(world, feet);
        Material belowMaterial = materialAt(world, feet.down());
        return feetMaterial != Material.water
                && belowMaterial != Material.water
                && isSolidGround(world, feet.down());
    }

    private boolean isPointNavigable(World world, RoutePoint point) {
        for (double xOffset : new double[]{-0.29, 0.29}) {
            for (double zOffset : new double[]{-0.29, 0.29}) {
                BlockPos feet = new BlockPos(point.x + xOffset, point.y, point.z + zOffset);
                if (!isNavigable(world, feet)) return false;
            }
        }
        return true;
    }

    private boolean isPassable(World world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        Material material = block.getMaterial();
        return material != Material.water && material != Material.lava && !material.isSolid();
    }

    private boolean isSolidGround(World world, BlockPos pos) {
        Material material = materialAt(world, pos);
        return material.isSolid() && material != Material.cactus;
    }

    private Material materialAt(World world, BlockPos pos) {
        return world.getBlockState(pos).getBlock().getMaterial();
    }

    private boolean isWater(World world, BlockPos pos) {
        return materialAt(world, pos) == Material.water;
    }

    private void followPath(Minecraft mc, EntityPlayer player, World world) {
        updatePathProgress(player);
        RoutePoint currentNode = travelPath.get(pathIndex);
        boolean stagingReady = stagingHoldTicks >= MIN_STAGING_HOLD_TICKS
                && stagingSideSweeps >= REQUIRED_ENTRY_SIDE_SWEEPS;
        if (currentNode.staging && reachedOrPassedStaging(player, currentNode, pathIndex)
                && !stagingReady
                && stagingHoldTicks < MAX_STAGING_HOLD_TICKS) {
            stagingHoldTicks++;
            releaseMovement();
            return;
        }
        if (!currentNode.staging
                || stagingReady
                || stagingHoldTicks >= MAX_STAGING_HOLD_TICKS) {
            stagingHoldTicks = 0;
        }
        while (pathIndex < travelPath.size() - 1
                && reachedRoutePoint(player, travelPath.get(pathIndex), pathIndex)) {
            pathIndex++;
        }

        RoutePoint node = chooseSteeringNode(world, player);
        boolean atFinalNode = pathIndex == travelPath.size() - 1;
        boolean finalNodeIsReturn = atFinalNode && activeReturnPoint != null
                && node.samePosition(activeReturnPoint);
        boolean finalReached = finalNodeIsReturn ? reachedReturnPoint(player) : reached(player, node);
        if (finalReached && atFinalNode) {
            releaseMovement();
            pathIndex = travelPath.size();
            if (finalNodeIsReturn) {
                beginRegrowthWait(player);
            } else if (!completionAnnounced) {
                completionAnnounced = true;
                player.addChatMessage(new ChatComponentText("\u00A7aFinished walking the mapped cane route."));
            }
            return;
        }

        double dx = node.x - player.posX;
        double dz = node.z - player.posZ;
        float desiredYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float relativeAngle = wrapDegrees(desiredYaw - player.rotationYaw);
        double angleRadians = Math.toRadians(relativeAngle);
        float forward = (float) Math.cos(angleRadians);
        float strafe = (float) -Math.sin(angleRadians);

        boolean swimming = player.isInWater() || isWater(world, player.getPosition());
        boolean stepUp = node.y > player.posY + 0.35;
        boolean jumpableObstacle = isJumpableObstacleAhead(world, player, node);
        if (!swimming && (stepUp || jumpableObstacle)) {
            jumpHoldTicks = Math.max(jumpHoldTicks, 4);
        } else if (swimming) {
            jumpHoldTicks = 0;
        } else if (jumpHoldTicks > 0) {
            jumpHoldTicks--;
        }
        boolean jump = jumpHoldTicks > 0;

        movementOwned = true;
        movementOverrideActive = true;
        overrideForward = forward;
        overrideStrafe = strafe;
        overrideJump = jump;
        setMovementKeys(mc, forward, strafe, jump);
        boolean approachingStaging = node.staging
                && horizontalDistanceSq(player, node) < 1.35 * 1.35;
        boolean sprint = !swimming && !approachingStaging;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), sprint);
        player.setSprinting(sprint);
        if (mc.thePlayer.movementInput != null) {
            mc.thePlayer.movementInput.moveForward = forward;
            mc.thePlayer.movementInput.moveStrafe = strafe;
            mc.thePlayer.movementInput.jump = jump;
        }
    }

    private boolean reachedReturnPoint(EntityPlayer player) {
        if (activeReturnPoint == null) return false;
        double dx = activeReturnPoint.x - player.posX;
        double dz = activeReturnPoint.z - player.posZ;
        return dx * dx + dz * dz <= 0.15 * 0.15
                && Math.abs(player.posY - activeReturnPoint.y) < 1.2;
    }

    private void beginRegrowthWait(EntityPlayer player) {
        waitingForRegrowth = true;
        regrowCheckTicks = 0;
        player.addChatMessage(new ChatComponentText(
                "\u00A7eReturned near home. Waiting for the mapped cane to regrow..."));
    }

    private void processRegrowthWait(Minecraft mc, World world, EntityPlayer player) {
        releaseMovementIfOwned();
        if (mc.currentScreen == null && firstAisleMiddlePoint != null) {
            turnCameraNaturally(player, firstAisleMiddlePoint.x,
                    player.posY + player.getEyeHeight(), firstAisleMiddlePoint.z,
                    false, false);
        }
        if (++regrowCheckTicks < REGROW_CHECK_INTERVAL_TICKS) return;
        regrowCheckTicks = 0;
        if (mappedCanePositions.isEmpty()) return;

        int ready = 0;
        for (BlockPos pos : mappedCanePositions) {
            if (isHarvestableMiddle(world, pos)) ready++;
        }
        int required = Math.max(1,
                (int) Math.ceil(mappedCanePositions.size() * REGROW_READY_RATIO));
        if (ready < required) return;

        markedCane.clear();
        caneBases.clear();
        for (BlockPos pos : mappedCanePositions) {
            if (isHarvestableMiddle(world, pos)) markedCane.add(pos);
            caneBases.add(pos.down());
        }

        List<Aisle> aisles = findCenterAisles(world);
        List<RoutePoint> rebuilt = buildSerpentineRoute(world, player, aisles);
        if (rebuilt.size() <= 1) return;

        travelPath.clear();
        travelPath.addAll(rebuilt);
        pathIndex = 1;
        waitingForRegrowth = false;
        completionAnnounced = false;
        breakTarget = null;
        targetLeftSide = true;
        harvestAisleId = -1;
        centeredEntryTicks = 0;
        stagingHoldTicks = 0;
        stagingSideSweeps = 0;
        jumpHoldTicks = 0;
        yawVelocity = 0.0F;
        pitchVelocity = 0.0F;
        player.addChatMessage(new ChatComponentText(
                "\u00A7aCane regrown (" + ready + "/" + mappedCanePositions.size()
                        + "). Starting another farming cycle."));
    }

    private boolean isJumpableObstacleAhead(World world, EntityPlayer player, RoutePoint node) {
        double dx = node.x - player.posX;
        double dz = node.z - player.posZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.10) return false;

        double sampleX = player.posX + dx / length * 0.68;
        double sampleZ = player.posZ + dz / length * 0.68;
        BlockPos obstacle = new BlockPos(sampleX, Math.floor(player.posY + 0.05), sampleZ);
        return isSolidGround(world, obstacle)
                && isPassable(world, obstacle.up())
                && isPassable(world, obstacle.up(2));
    }

    private void updateHeadAndBreak(Minecraft mc, EntityPlayer player, World world,
                                    RoutePoint steeringNode) {
        markedCane.removeIf(pos -> !isHarvestableMiddle(world, pos));
        int aisleIndex = findActiveAisleIndex(player);
        int aisleId = aisleIndex < 0 ? -1 : travelPath.get(aisleIndex).aisleId;
        int nodesToEnd = aisleIndex < 0 ? 0 : nodesUntilAisleEnd(aisleIndex, aisleId);
        boolean fastTransitionTurn = (aisleId >= 0 && nodesToEnd <= TURN_PREPARE_NODES)
                || isNearUpcomingAisle(player, 2.7) || isReturningHome();
        boolean insideAisle = aisleIndex >= 0 && isInsideAisle(player, aisleIndex, aisleId);
        setAttackHeld(mc, insideAisle);

        if (insideAisle && harvestAisleId != aisleId) {
            mc.playerController.resetBlockRemoving();
            breakTarget = null;
            breakAttemptTicks = 0;
            breakDelay = 0;
            targetLeftSide = true;
            harvestAisleId = aisleId;
            centeredEntryTicks = 1;
            stagingSideSweeps = 0;
            yawVelocity *= 0.40F;
            pitchVelocity *= 0.40F;
        }

        if (breakTarget != null && !isHarvestableMiddle(world, breakTarget)) {
            finishBreakTarget(mc, true, true);
        }

        if (breakTarget != null && (eyeDistanceSq(player, breakTarget) > BREAK_REACH * BREAK_REACH
                || aisleId < 0
                || !insideAisle
                || !isBreakTargetAhead(player, breakTarget, aisleIndex, aisleId))) {
            mc.playerController.resetBlockRemoving();
            finishBreakTarget(mc, false, breakAttemptTicks > 0);
        }

        if (insideAisle && centeredEntryTicks > 0) {
            RoutePoint centerLook = chooseAisleCenterLookNode(aisleIndex, aisleId);
            turnCameraNaturally(player, centerLook.x,
                    player.posY + player.getEyeHeight(), centerLook.z, false, true);
            centeredEntryTicks--;
            return;
        }

        if (breakTarget == null && aisleId >= 0 && insideAisle && nodesToEnd > 0) {
            breakTarget = chooseBreakTarget(player, world, aisleIndex, aisleId);
            if (breakTarget != null) {
                aimOffsetX = randomRange(-0.055, 0.055);
                aimOffsetY = randomRange(-0.06, 0.075);
                aimOffsetZ = randomRange(-0.055, 0.055);
                breakDelay = 0;
                breakAttemptTicks = 0;
            }
        }

        if (breakTarget != null) {
            double targetX = breakTarget.getX() + 0.5 + aimOffsetX;
            double targetY = breakTarget.getY() + 0.48 + aimOffsetY;
            double targetZ = breakTarget.getZ() + 0.5 + aimOffsetZ;
            double[] aimDirection = aisleDirection(aisleIndex, aisleId);
            if (aimDirection != null) {
                double leftX = aimDirection[1];
                double leftZ = -aimDirection[0];
                double lateral = (targetX - player.posX) * leftX
                        + (targetZ - player.posZ) * leftZ;
                double sideOutset = lateral >= 0.0 ? SIDE_AIM_OUTSET : -SIDE_AIM_OUTSET;
                targetX += leftX * sideOutset;
                targetZ += leftZ * sideOutset;
            }
            CameraDifference difference = turnCameraNaturally(
                    player, targetX, targetY, targetZ, true, true);

            if (breakDelay > 0) breakDelay--;
            if (breakDelay <= 0 && Math.abs(difference.yaw) < 4.2F
                    && Math.abs(difference.pitch) < 3.7F) {
                BlockPos damageTarget = chooseNearestCaneOnAimedSide(
                        player, world, aisleIndex, aisleId, breakTarget);
                if (damageTarget != null) {
                    EnumFacing face = faceTowardPlayer(player, damageTarget);
                    mc.playerController.clickBlock(damageTarget, face);
                    mc.playerController.onPlayerDamageBlock(damageTarget, face);
                    breakAttemptTicks++;
                    if (breakAttemptTicks >= SIDE_HOLD_TICKS) advanceToOtherCaneSide();
                    return;
                }
                breakAttemptTicks++;

                if (!isHarvestableMiddle(world, breakTarget)) {
                    finishBreakTarget(mc, true, true);
                }
            }
            return;
        }

        // Keep the initial approach level and looking down the route. At a row end
        // and on its short connector, use a faster turn profile so the next aisle
        // is already in view when the player enters it.
        RoutePoint lookNode = chooseCameraLookNode(player, steeringNode, fastTransitionTurn);
        turnCameraNaturally(player, lookNode.x,
                player.posY + player.getEyeHeight(), lookNode.z, false, fastTransitionTurn);
    }

    private RoutePoint chooseCameraLookNode(EntityPlayer player, RoutePoint steeringNode,
                                            boolean fastTransitionTurn) {
        RoutePoint aisleEntry = chooseUpcomingAisleEntry();
        if (aisleEntry != null) {
            double entryDistance = Math.sqrt(horizontalDistanceSq(player, aisleEntry));
            if (entryDistance <= 2.7) {
                RoutePoint rowLook = chooseUpcomingAisleForwardPoint();
                return rowLook != null ? rowLook : aisleEntry;
            }
            double maximumPreview = currentAisleId() < 0 && !hasPreviousAisle()
                    ? 4.2 : 3.2;
            double lookAhead = Math.min(maximumPreview,
                    Math.max(1.2, entryDistance - 0.8));
            return chooseSmoothRouteLookPoint(player, steeringNode, lookAhead, -1);
        }
        if (fastTransitionTurn && activeReturnPoint != null) {
            return chooseSmoothRouteLookPoint(player, activeReturnPoint, 3.4, -1);
        }
        return chooseSmoothRouteLookPoint(player, steeringNode, 4.8, currentAisleId());
    }

    private RoutePoint chooseSmoothRouteLookPoint(EntityPlayer player, RoutePoint fallback,
                                                   double lookAheadDistance,
                                                   int requiredAisleId) {
        double fromX = player.posX;
        double fromY = player.posY;
        double fromZ = player.posZ;
        double remaining = lookAheadDistance;
        RoutePoint last = fallback;
        int furthest = Math.min(pathIndex + 18, travelPath.size() - 1);

        for (int i = pathIndex; i <= furthest; i++) {
            RoutePoint candidate = travelPath.get(i);
            if (requiredAisleId >= 0 && candidate.aisleId != requiredAisleId) break;

            double dx = candidate.x - fromX;
            double dy = candidate.y - fromY;
            double dz = candidate.z - fromZ;
            double segmentLength = Math.sqrt(dx * dx + dz * dz);
            if (segmentLength > 0.001 && segmentLength >= remaining) {
                double fraction = remaining / segmentLength;
                return new RoutePoint(
                        fromX + dx * fraction,
                        fromY + dy * fraction,
                        fromZ + dz * fraction,
                        candidate.aisleId);
            }
            if (segmentLength > 0.001) remaining -= segmentLength;
            fromX = candidate.x;
            fromY = candidate.y;
            fromZ = candidate.z;
            last = candidate;
        }
        return last;
    }

    private RoutePoint chooseUpcomingAisleEntry() {
        int currentId = currentAisleId();
        for (int i = pathIndex; i < travelPath.size(); i++) {
            int aisleId = travelPath.get(i).aisleId;
            if (aisleId >= 0 && aisleId != currentId) return travelPath.get(i);
        }
        return null;
    }

    private RoutePoint chooseUpcomingAisleForwardPoint() {
        int currentId = currentAisleId();
        for (int i = pathIndex; i < travelPath.size(); i++) {
            RoutePoint entry = travelPath.get(i);
            if (entry.aisleId < 0 || entry.aisleId == currentId) continue;
            int lookIndex = i;
            for (int step = 0; step < 3 && lookIndex + 1 < travelPath.size(); step++) {
                if (travelPath.get(lookIndex + 1).aisleId != entry.aisleId) break;
                lookIndex++;
            }
            return travelPath.get(lookIndex);
        }
        return null;
    }

    private boolean isNearUpcomingAisle(EntityPlayer player, double distance) {
        RoutePoint entry = chooseUpcomingAisleEntry();
        return entry != null && horizontalDistanceSq(player, entry) <= distance * distance;
    }

    private boolean isInsideAisle(EntityPlayer player, int aisleIndex, int aisleId) {
        double[] direction = aisleDirection(aisleIndex, aisleId);
        if (direction == null) return false;

        int startIndex = aisleIndex;
        while (startIndex > 0 && travelPath.get(startIndex - 1).aisleId == aisleId) startIndex--;
        int endIndex = aisleIndex;
        while (endIndex + 1 < travelPath.size()
                && travelPath.get(endIndex + 1).aisleId == aisleId) endIndex++;

        RoutePoint start = travelPath.get(startIndex);
        RoutePoint end = travelPath.get(endIndex);
        double dx = player.posX - start.x;
        double dz = player.posZ - start.z;
        double along = dx * direction[0] + dz * direction[1];
        double lateral = Math.abs(dx * -direction[1] + dz * direction[0]);
        double length = Math.sqrt((end.x - start.x) * (end.x - start.x)
                + (end.z - start.z) * (end.z - start.z));
        return along >= -0.10 && along <= length + 0.40 && lateral <= 0.82;
    }

    private BlockPos chooseBreakTarget(EntityPlayer player, World world,
                                       int aisleIndex, int aisleId) {
        double[] direction = aisleDirection(aisleIndex, aisleId);
        if (direction == null) return null;

        double forwardX = direction[0];
        double forwardZ = direction[1];
        // Minecraft's +Z axis points south, so this is the true player-left
        // perpendicular for the current forward direction.
        double leftX = forwardZ;
        double leftZ = -forwardX;
        BlockPos preferred = null;
        BlockPos fallback = null;
        double preferredScore = Double.MAX_VALUE;
        double fallbackScore = Double.MAX_VALUE;

        for (BlockPos cane : markedCane) {
            if (!isHarvestableMiddle(world, cane)) continue;

            double dx = cane.getX() + 0.5 - player.posX;
            double dz = cane.getZ() + 0.5 - player.posZ;
            double projection = dx * forwardX + dz * forwardZ;
            // Aim a couple of blocks ahead instead of snapping sideways at cane
            // directly beside the player.
            if (projection < 0.08 || projection > 4.00) continue;

            double lateral = dx * leftX + dz * leftZ;
            double absoluteLateral = Math.abs(lateral);
            if (absoluteLateral < 0.12 || absoluteLateral > 1.05) continue;
            if (eyeDistanceSq(player, cane) > BREAK_REACH * BREAK_REACH) continue;

            boolean atStaging = pathIndex >= 0 && pathIndex < travelPath.size()
                    && travelPath.get(pathIndex).staging;
            double idealProjection = atStaging ? 1.0 : 3.55;
            double score = Math.abs(projection - idealProjection) * 2.2
                    + Math.abs(absoluteLateral - 0.5) * 3.0
                    + Math.abs((cane.getY() + 0.5) - (player.posY + player.getEyeHeight())) * 0.15;
            boolean onPreferredSide = targetLeftSide ? lateral > 0.0 : lateral < 0.0;
            if (onPreferredSide && score < preferredScore) {
                preferredScore = score;
                preferred = cane;
            } else if (!onPreferredSide && score < fallbackScore) {
                fallbackScore = score;
                fallback = cane;
            }
        }
        return preferred != null ? preferred : fallback;
    }

    private BlockPos chooseNearestCaneOnAimedSide(EntityPlayer player, World world,
                                                   int aisleIndex, int aisleId,
                                                   BlockPos aimedTarget) {
        double[] direction = aisleDirection(aisleIndex, aisleId);
        if (direction == null) return null;
        double leftX = direction[1];
        double leftZ = -direction[0];
        double aimedDx = aimedTarget.getX() + 0.5 - player.posX;
        double aimedDz = aimedTarget.getZ() + 0.5 - player.posZ;
        boolean aimedLeft = aimedDx * leftX + aimedDz * leftZ >= 0.0;

        BlockPos nearest = null;
        double nearestProjection = Double.MAX_VALUE;
        for (BlockPos cane : markedCane) {
            if (!isHarvestableMiddle(world, cane)) continue;
            double dx = cane.getX() + 0.5 - player.posX;
            double dz = cane.getZ() + 0.5 - player.posZ;
            double projection = dx * direction[0] + dz * direction[1];
            if (projection < 0.0 || projection > 4.05) continue;
            double lateral = dx * leftX + dz * leftZ;
            if ((lateral >= 0.0) != aimedLeft) continue;
            double absoluteLateral = Math.abs(lateral);
            if (absoluteLateral < 0.12 || absoluteLateral > 1.05) continue;
            if (eyeDistanceSq(player, cane) > BREAK_REACH * BREAK_REACH) continue;
            if (projection < nearestProjection) {
                nearestProjection = projection;
                nearest = cane;
            }
        }
        return nearest;
    }

    private void advanceToOtherCaneSide() {
        if (pathIndex >= 0 && pathIndex < travelPath.size()
                && travelPath.get(pathIndex).staging) {
            stagingSideSweeps++;
        }
        targetLeftSide = !targetLeftSide;
        breakTarget = null;
        breakAttemptTicks = 0;
        breakDelay = 0;
        yawVelocity *= 0.72F;
        pitchVelocity *= 0.72F;
    }

    private RoutePoint chooseAisleCenterLookNode(int aisleIndex, int aisleId) {
        int lookIndex = aisleIndex;
        for (int step = 0; step < 3 && lookIndex + 1 < travelPath.size(); step++) {
            if (travelPath.get(lookIndex + 1).aisleId != aisleId) break;
            lookIndex++;
        }
        return travelPath.get(lookIndex);
    }

    private int findActiveAisleIndex(EntityPlayer player) {
        if (pathIndex < 0 || pathIndex >= travelPath.size()) return -1;
        if (travelPath.get(pathIndex).aisleId >= 0) return pathIndex;

        int furthest = Math.min(pathIndex + 12, travelPath.size() - 1);
        int bestIndex = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = pathIndex + 1; i <= furthest; i++) {
            RoutePoint point = travelPath.get(i);
            if (point.aisleId < 0 || Math.abs(point.y - player.posY) > 2.25) continue;
            double distance = horizontalDistanceSq(player, point);
            if (distance <= 6.25 * 6.25 && distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private int currentAisleId() {
        if (pathIndex < 0 || pathIndex >= travelPath.size()) return -1;
        return travelPath.get(pathIndex).aisleId;
    }

    private int nodesUntilAisleEnd(int aisleIndex, int aisleId) {
        int count = 0;
        for (int i = aisleIndex; i < travelPath.size(); i++) {
            if (travelPath.get(i).aisleId != aisleId) break;
            count++;
        }
        return count;
    }

    private boolean isBetweenAisles() {
        if (currentAisleId() >= 0) return false;
        boolean previousAisle = hasPreviousAisle();
        boolean nextAisle = false;
        for (int i = pathIndex + 1; i < travelPath.size(); i++) {
            if (travelPath.get(i).aisleId >= 0) {
                nextAisle = true;
                break;
            }
        }
        return previousAisle && nextAisle;
    }

    private boolean isReturningHome() {
        if (currentAisleId() >= 0 || activeReturnPoint == null || !hasPreviousAisle()) return false;
        for (int i = pathIndex; i < travelPath.size(); i++) {
            if (travelPath.get(i).aisleId >= 0) return false;
        }
        return true;
    }

    private boolean hasPreviousAisle() {
        for (int i = pathIndex - 1; i >= 0; i--) {
            if (travelPath.get(i).aisleId >= 0) return true;
        }
        return false;
    }

    private double[] aisleDirection(int aisleIndex, int aisleId) {
        RoutePoint current = travelPath.get(aisleIndex);
        for (int i = aisleIndex + 1; i < travelPath.size(); i++) {
            RoutePoint next = travelPath.get(i);
            if (next.aisleId != aisleId) break;
            double dx = next.x - current.x;
            double dz = next.z - current.z;
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 0.001) return new double[]{dx / length, dz / length};
        }
        for (int i = aisleIndex - 1; i >= 0; i--) {
            RoutePoint previous = travelPath.get(i);
            if (previous.aisleId != aisleId) break;
            double dx = current.x - previous.x;
            double dz = current.z - previous.z;
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 0.001) return new double[]{dx / length, dz / length};
        }
        return null;
    }

    private CameraDifference turnCameraNaturally(EntityPlayer player, double targetX,
                                                  double targetY, double targetZ,
                                                  boolean preciseAim, boolean fastTurn) {
        double dx = targetX - player.posX;
        double dy = targetY - (player.posY + player.getEyeHeight());
        double dz = targetZ - player.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.05) {
            yawVelocity *= 0.45F;
            pitchVelocity *= 0.45F;
            return new CameraDifference(0.0F, 0.0F);
        }
        float targetYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float targetPitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

        double time = player.ticksExisted * 0.115 + cameraNoiseSeed;
        float driftScale = preciseAim ? 0.16F : 0.34F;
        targetYaw += (float) ((Math.sin(time) + Math.sin(time * 0.43 + 1.7) * 0.45) * driftScale);
        targetPitch += (float) ((Math.sin(time * 0.71 + 0.8) + Math.sin(time * 0.29) * 0.35)
                * driftScale * 0.62F);

        float yawError = wrapDegrees(targetYaw - player.rotationYaw);
        float pitchError = targetPitch - player.rotationPitch;
        float yawResponse = fastTurn ? 0.44F : 0.27F;
        float pitchResponse = fastTurn ? 0.37F : 0.24F;
        float maximumYawSpeed = fastTurn ? 16.0F : 9.2F;
        float maximumPitchSpeed = fastTurn ? 8.8F : 5.8F;
        float yawAcceleration = fastTurn ? 3.05F : 1.48F;
        float pitchAcceleration = fastTurn ? 1.72F : 0.94F;
        float yawDeceleration = fastTurn ? 4.15F : 2.15F;
        float pitchDeceleration = fastTurn ? 2.35F : 1.35F;
        float wantedYawVelocity = brakingVelocity(
                yawError, yawResponse, maximumYawSpeed, yawDeceleration);
        float wantedPitchVelocity = brakingVelocity(
                pitchError, pitchResponse, maximumPitchSpeed, pitchDeceleration);
        yawVelocity = approachCameraVelocity(
                yawVelocity, wantedYawVelocity, yawAcceleration, yawDeceleration);
        pitchVelocity = approachCameraVelocity(
                pitchVelocity, wantedPitchVelocity, pitchAcceleration, pitchDeceleration);

        float yawStep = Math.abs(yawVelocity) > Math.abs(yawError) ? yawError : yawVelocity;
        float pitchStep = Math.abs(pitchVelocity) > Math.abs(pitchError) ? pitchError : pitchVelocity;
        player.rotationYaw += yawStep;
        player.rotationPitch = clamp(player.rotationPitch + pitchStep, -88.0F, 88.0F);
        player.rotationYawHead = player.rotationYaw;
        if (yawStep == yawError) yawVelocity *= 0.22F;
        if (pitchStep == pitchError) pitchVelocity *= 0.22F;

        return new CameraDifference(
                wrapDegrees(targetYaw - player.rotationYaw),
                targetPitch - player.rotationPitch);
    }

    private float brakingVelocity(float error, float response, float maximumSpeed,
                                  float deceleration) {
        float stoppingSpeed = (float) Math.sqrt(2.0F * deceleration * Math.abs(error));
        float speed = Math.min(maximumSpeed,
                Math.min(Math.abs(error) * response, stoppingSpeed));
        return Math.copySign(speed, error);
    }

    private float approachCameraVelocity(float current, float wanted,
                                         float acceleration, float deceleration) {
        boolean braking = current != 0.0F
                && (Math.signum(current) != Math.signum(wanted)
                || Math.abs(wanted) < Math.abs(current));
        float limit = braking ? deceleration : acceleration;
        return current + clamp(wanted - current, -limit, limit);
    }

    private boolean isHarvestableMiddle(World world, BlockPos pos) {
        return isReed(world, pos) && isReed(world, pos.down()) && !isReed(world, pos.down().down());
    }

    private double eyeDistanceSq(EntityPlayer player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.posX;
        double dy = pos.getY() + 0.5 - (player.posY + player.getEyeHeight());
        double dz = pos.getZ() + 0.5 - player.posZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private EnumFacing faceTowardPlayer(EntityPlayer player, BlockPos pos) {
        double dx = player.posX - (pos.getX() + 0.5);
        double dz = player.posZ - (pos.getZ() + 0.5);
        if (Math.abs(dx) > Math.abs(dz)) return dx >= 0.0 ? EnumFacing.EAST : EnumFacing.WEST;
        return dz >= 0.0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
    }

    private boolean isBreakTargetAhead(EntityPlayer player, BlockPos target,
                                       int aisleIndex, int aisleId) {
        if (aisleIndex < 0 || aisleId < 0) return false;
        double[] direction = aisleDirection(aisleIndex, aisleId);
        if (direction == null) return false;
        double dx = target.getX() + 0.5 - player.posX;
        double dz = target.getZ() + 0.5 - player.posZ;
        double projection = dx * direction[0] + dz * direction[1];
        return projection >= 0.0 && projection <= 4.30;
    }

    private void finishBreakTarget(Minecraft mc, boolean broken, boolean alternateSide) {
        if (broken && pathIndex >= 0 && pathIndex < travelPath.size()
                && travelPath.get(pathIndex).staging) {
            stagingSideSweeps++;
        }
        if (broken && breakTarget != null) markedCane.remove(breakTarget);
        if (alternateSide) targetLeftSide = !targetLeftSide;
        breakTarget = null;
        breakAttemptTicks = 0;
        breakDelay = broken ? 1 + (int) (Math.random() * 2.0) : 2;
        yawVelocity *= 0.40F;
        pitchVelocity *= 0.40F;
    }

    private void setAttackHeld(Minecraft mc, boolean held) {
        int attackKey = mc.gameSettings.keyBindAttack.getKeyCode();
        if (held) {
            KeyBinding.setKeyBindState(attackKey, true);
            if (!attackOwned) {
                KeyBinding.onTick(attackKey);
                attackOwned = true;
            }
        } else if (attackOwned) {
            KeyBinding.setKeyBindState(attackKey, false);
            attackOwned = false;
        }
    }

    private double randomRange(double minimum, double maximum) {
        return minimum + Math.random() * (maximum - minimum);
    }

    private float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void updatePathProgress(EntityPlayer player) {
        int furthest = Math.min(pathIndex + PATH_CATCHUP_WINDOW, travelPath.size() - 1);
        for (int i = pathIndex; i <= furthest; i++) {
            if (travelPath.get(i).staging) {
                furthest = i;
                break;
            }
        }
        int bestIndex = pathIndex;
        double bestDistance = horizontalDistanceSq(player, travelPath.get(pathIndex));

        for (int i = pathIndex + 1; i <= furthest; i++) {
            double distance = horizontalDistanceSq(player, travelPath.get(i));
            if (distance < 0.75 * 0.75 && distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        pathIndex = bestIndex;
    }

    private RoutePoint chooseSteeringNode(World world, EntityPlayer player) {
        int furthest = Math.min(pathIndex + STEERING_LOOKAHEAD_NODES, travelPath.size() - 1);
        for (int i = pathIndex; i <= furthest; i++) {
            if (travelPath.get(i).staging) {
                furthest = i;
                break;
            }
        }
        for (int i = furthest; i > pathIndex; i--) {
            RoutePoint candidate = travelPath.get(i);
            if (hasSafeDirectLine(world, player, candidate)) return candidate;
        }
        return travelPath.get(pathIndex);
    }

    private boolean hasSafeDirectLine(World world, EntityPlayer player, RoutePoint target) {
        return hasSafeDirectLine(world,
                new RoutePoint(player.posX, player.posY, player.posZ), target);
    }

    private boolean hasSafeDirectLine(World world, RoutePoint start, RoutePoint target) {
        double startX = start.x;
        double startY = start.y;
        double startZ = start.z;
        double endX = target.x;
        double endY = target.y;
        double endZ = target.z;
        double dx = endX - startX;
        double dy = endY - startY;
        double dz = endZ - startZ;
        int samples = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) * 4.0));

        for (int i = 1; i <= samples; i++) {
            double progress = i / (double) samples;
            RoutePoint sample = new RoutePoint(
                    startX + dx * progress,
                    startY + dy * progress,
                    startZ + dz * progress);
            if (!isPointNavigable(world, sample)) return false;
        }
        return true;
    }

    private double horizontalDistanceSq(EntityPlayer player, RoutePoint node) {
        double dx = node.x - player.posX;
        double dz = node.z - player.posZ;
        return dx * dx + dz * dz;
    }

    private void setMovementKeys(Minecraft mc, float forward, float strafe, boolean jump) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), forward > 0.15F);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), forward < -0.15F);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), strafe > 0.15F);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), strafe < -0.15F);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), jump);
    }

    public static void applyMovementOverride(MovementInput input) {
        if (!movementOverrideActive) return;
        input.moveForward = overrideForward;
        input.moveStrafe = overrideStrafe;
        input.jump = overrideJump;
        input.sneak = false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && !mc.thePlayer.isInWater()) {
            mc.thePlayer.setSprinting(true);
        }
    }

    private boolean reached(EntityPlayer player, RoutePoint node) {
        double dx = node.x - player.posX;
        double dz = node.z - player.posZ;
        return dx * dx + dz * dz <= NODE_REACHED_DISTANCE * NODE_REACHED_DISTANCE
                && Math.abs(player.posY - node.y) < 1.2;
    }

    private boolean reachedRoutePoint(EntityPlayer player, RoutePoint node, int nodeIndex) {
        return node.staging ? reachedOrPassedStaging(player, node, nodeIndex)
                : reached(player, node);
    }

    private boolean reachedOrPassedStaging(EntityPlayer player, RoutePoint staging,
                                           int stagingIndex) {
        if (reached(player, staging)) return true;
        double[] direction = aisleDirection(stagingIndex, staging.aisleId);
        if (direction == null || Math.abs(player.posY - staging.y) >= 1.2) return false;

        double dx = player.posX - staging.x;
        double dz = player.posZ - staging.z;
        double forward = dx * direction[0] + dz * direction[1];
        double lateral = Math.abs(dx * -direction[1] + dz * direction[0]);
        return forward >= 0.0 && forward <= 0.85 && lateral <= 0.72;
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!active) return;
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
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();

        GlStateManager.color(1.0F, 0.05F, 0.05F, 0.85F);
        GL11.glLineWidth(2.0F);
        for (BlockPos cane : markedCane) drawBox(renderer, tessellator, cane, px, py, pz, 0.94);

        if (!travelPath.isEmpty()) {
            GlStateManager.color(0.1F, 1.0F, 0.3F, 0.9F);
            GL11.glLineWidth(3.0F);
            renderer.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION);
            for (RoutePoint node : travelPath) {
                renderer.pos(node.x - px, node.y + 0.12 - py, node.z - pz).endVertex();
            }
            tessellator.draw();
        }

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private void drawBox(WorldRenderer renderer, Tessellator tessellator, BlockPos pos,
                         double px, double py, double pz, double scale) {
        double inset = (1.0 - scale) / 2.0;
        double minX = pos.getX() + inset - px;
        double minY = pos.getY() + inset - py;
        double minZ = pos.getZ() + inset - pz;
        double maxX = minX + scale;
        double maxY = minY + scale;
        double maxZ = minZ + scale;
        renderer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);
        edge(renderer, minX, minY, minZ, maxX, minY, minZ);
        edge(renderer, maxX, minY, minZ, maxX, minY, maxZ);
        edge(renderer, maxX, minY, maxZ, minX, minY, maxZ);
        edge(renderer, minX, minY, maxZ, minX, minY, minZ);
        edge(renderer, minX, maxY, minZ, maxX, maxY, minZ);
        edge(renderer, maxX, maxY, minZ, maxX, maxY, maxZ);
        edge(renderer, maxX, maxY, maxZ, minX, maxY, maxZ);
        edge(renderer, minX, maxY, maxZ, minX, maxY, minZ);
        edge(renderer, minX, minY, minZ, minX, maxY, minZ);
        edge(renderer, maxX, minY, minZ, maxX, maxY, minZ);
        edge(renderer, maxX, minY, maxZ, maxX, maxY, maxZ);
        edge(renderer, minX, minY, maxZ, minX, maxY, maxZ);
        tessellator.draw();
    }

    private void edge(WorldRenderer renderer, double x1, double y1, double z1,
                      double x2, double y2, double z2) {
        renderer.pos(x1, y1, z1).endVertex();
        renderer.pos(x2, y2, z2).endVertex();
    }

    private void appendConnector(World world, List<RoutePoint> destination,
                                 List<BlockPos> connector) {
        if (connector.size() < 2) return;
        int anchor = 0;
        while (anchor < connector.size() - 1) {
            RoutePoint start = destination.isEmpty()
                    ? routePoint(connector.get(anchor))
                    : destination.get(destination.size() - 1);
            int chosen = anchor + 1;
            int furthest = Math.min(anchor + 12, connector.size() - 1);
            for (int candidate = furthest; candidate > anchor; candidate--) {
                RoutePoint target = routePoint(connector.get(candidate));
                if (hasSafeDirectLine(world, start, target)) {
                    chosen = candidate;
                    break;
                }
            }
            appendRoutePoint(destination, routePoint(connector.get(chosen)));
            anchor = chosen;
        }
    }

    private RoutePoint routePoint(BlockPos pos) {
        return new RoutePoint(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private void appendRoutePoints(List<RoutePoint> destination, List<RoutePoint> source) {
        for (RoutePoint point : source) appendRoutePoint(destination, point);
    }

    private void appendRoutePoint(List<RoutePoint> destination, RoutePoint point) {
        if (!destination.isEmpty() && destination.get(destination.size() - 1).samePosition(point)) {
            if (point.aisleId >= 0) destination.set(destination.size() - 1, point);
        } else {
            destination.add(point);
        }
    }

    private List<BlockPos> reconstruct(PathNode end) {
        List<BlockPos> path = new ArrayList<>();
        for (PathNode node = end; node != null; node = node.parent) path.add(node.pos);
        Collections.reverse(path);
        return path;
    }

    private double heuristic(BlockPos from, BlockPos to) {
        int dx = Math.abs(from.getX() - to.getX());
        int dz = Math.abs(from.getZ() - to.getZ());
        int diagonal = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diagonal;
        return diagonal * Math.sqrt(2.0) + straight
                + Math.abs(from.getY() - to.getY());
    }

    private float wrapDegrees(float value) {
        value %= 360.0F;
        if (value >= 180.0F) value -= 360.0F;
        if (value < -180.0F) value += 360.0F;
        return value;
    }

    private boolean isReed(World world, BlockPos pos) {
        return world.getBlockState(pos).getBlock() == Blocks.reeds;
    }

    private boolean isHoldingHoe(EntityPlayer player) {
        return player.getHeldItem() != null
                && player.getHeldItem().getItem() instanceof ItemHoe;
    }

    private long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private void stopAndClear() {
        restorePauseOnLostFocus();
        active = false;
        scanning = false;
        waitingForRegrowth = false;
        regrowCheckTicks = 0;
        mappedWorld = null;
        homePoint = null;
        activeReturnPoint = null;
        firstAisleMiddlePoint = null;
        homeBlock = null;
        lastReturnBlock = null;
        returnVariantCursor = 0;
        mappedCanePositions.clear();
        markedCane.clear();
        caneBases.clear();
        travelPath.clear();
        pathIndex = 0;
        jumpHoldTicks = 0;
        stagingHoldTicks = 0;
        stagingSideSweeps = 0;
        breakTarget = null;
        targetLeftSide = true;
        harvestAisleId = -1;
        centeredEntryTicks = 0;
        breakDelay = 0;
        breakAttemptTicks = 0;
        yawVelocity = 0.0F;
        pitchVelocity = 0.0F;
        releaseMovement();
    }

    private void restorePauseOnLostFocus() {
        if (!pauseSettingCaptured) return;
        Minecraft.getMinecraft().gameSettings.pauseOnLostFocus = previousPauseOnLostFocus;
        pauseSettingCaptured = false;
    }

    private void releaseMovementIfOwned() {
        if (movementOwned) releaseMovement();
    }

    private void releaseMovement() {
        Minecraft mc = Minecraft.getMinecraft();
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        if (attackOwned) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
            attackOwned = false;
        }
        if (mc.thePlayer != null) mc.thePlayer.setSprinting(false);
        if (mc.thePlayer != null && mc.thePlayer.movementInput != null) {
            mc.thePlayer.movementInput.moveForward = 0.0F;
            mc.thePlayer.movementInput.moveStrafe = 0.0F;
            mc.thePlayer.movementInput.jump = false;
        }
        movementOverrideActive = false;
        overrideForward = 0.0F;
        overrideStrafe = 0.0F;
        overrideJump = false;
        movementOwned = false;
    }

    private static class ReturnDestination {
        final RoutePoint point;
        final List<BlockPos> path;

        ReturnDestination(RoutePoint point, List<BlockPos> path) {
            this.point = point;
            this.path = path;
        }
    }

    private static class Aisle {
        final List<RoutePoint> nodes;

        Aisle(List<RoutePoint> nodes) {
            this.nodes = nodes;
        }

        RoutePoint first() {
            return nodes.get(0);
        }

        RoutePoint last() {
            return nodes.get(nodes.size() - 1);
        }
    }

    private static class RoutePoint {
        final double x;
        final double y;
        final double z;
        final int aisleId;
        final boolean staging;

        RoutePoint(double x, double y, double z) {
            this(x, y, z, -1, false);
        }

        RoutePoint(double x, double y, double z, int aisleId) {
            this(x, y, z, aisleId, false);
        }

        RoutePoint(double x, double y, double z, int aisleId, boolean staging) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.aisleId = aisleId;
            this.staging = staging;
        }

        double distanceSq(RoutePoint other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }

        boolean samePosition(RoutePoint other) {
            return Math.abs(x - other.x) < 0.001
                    && Math.abs(y - other.y) < 0.001
                    && Math.abs(z - other.z) < 0.001;
        }
    }

    private static class CameraDifference {
        final float yaw;
        final float pitch;

        CameraDifference(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static class PathNode {
        final BlockPos pos;
        final double g;
        final double f;
        final PathNode parent;

        PathNode(BlockPos pos, double g, double f, PathNode parent) {
            this.pos = pos;
            this.g = g;
            this.f = f;
            this.parent = parent;
        }
    }
}
