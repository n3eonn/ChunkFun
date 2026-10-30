package dev.chunkfun.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Finds safe positions inside a {@link PlayableArea}.
 *
 * <p>All methods read blocks through {@link World#getType(int, int, int)} and must run on the main thread.
 * Callers only pass columns in loaded chunks (single-chunk areas are held loaded by a plugin ticket;
 * large areas filter with {@link World#isChunkLoaded(int, int)}), so a scan never loads chunks
 * synchronously.</p>
 */
public final class SurfaceScanner {

    /** Returned when a column has no usable position. */
    public static final int NONE = Integer.MIN_VALUE;

    private static final Set<Material> DANGEROUS = EnumSet.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE,
            Material.POWDER_SNOW, Material.COBWEB, Material.POINTED_DRIPSTONE, Material.END_PORTAL,
            Material.NETHER_PORTAL, Material.END_GATEWAY,
            // Ancient City: never put players or loot on top of blocks that summon the Warden.
            Material.SCULK_SHRIEKER, Material.SCULK_SENSOR, Material.CALIBRATED_SCULK_SENSOR);

    private static final Set<Material> WATER_LIKE = EnumSet.of(
            Material.WATER, Material.BUBBLE_COLUMN, Material.KELP, Material.KELP_PLANT,
            Material.SEAGRASS, Material.TALL_SEAGRASS);

    private SurfaceScanner() {
    }

    public static boolean isDangerous(Material material) {
        return DANGEROUS.contains(material);
    }

    public static boolean isWaterLike(Material material) {
        return WATER_LIKE.contains(material);
    }

    /** Whether an entity can occupy this block (air, plants, optionally water). */
    public static boolean isPassable(Material material, boolean allowWater) {
        if (material.isAir()) {
            return true;
        }
        if (DANGEROUS.contains(material)) {
            return false;
        }
        if (WATER_LIKE.contains(material)) {
            return allowWater;
        }
        return !material.isSolid();
    }

    /** Whether an entity can safely stand on top of this block. Leaves are excluded so spawns land on the ground. */
    public static boolean isSafeFloor(Material material) {
        return material.isSolid()
                && !DANGEROUS.contains(material)
                && !Tag.LEAVES.isTagged(material)
                && !Tag.FENCES.isTagged(material)
                && !Tag.WALLS.isTagged(material)
                && !Tag.FENCE_GATES.isTagged(material);
    }

    /**
     * First Y worth scanning from in a column, capped by the area. It starts a few blocks above the highest
     * motion-blocking block so the open space above the surface is counted: starting directly on top of it would
     * see only one free block and reject every surface spot that needs two (players) or more (tall mobs).
     */
    public static int scanTop(PlayableArea area, int x, int z) {
        int highest = area.world().getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
        return Math.min(area.maxY(), highest + 5);
    }

    /**
     * Scans a column top-down for the surface: the first block below open space (ignoring leaf canopies) must
     * be a safe floor with {@code clearance} passable blocks above it. If the surface is water (when not
     * allowed), lava or another unsafe block, the column is rejected instead of searching caves below it.
     * Solid blocks before the first open space (the Nether roof, the rock above the Ancient City) are skipped.
     *
     * @return the feet Y, or {@link #NONE}
     */
    public static int standY(World world, int x, int z, int topY, int bottomY, int clearance, boolean allowWater) {
        int free = 0;
        boolean open = false;
        for (int y = topY; y >= bottomY; y--) {
            Material material = world.getType(x, y, z);
            if (isPassable(material, allowWater)) {
                free++;
                open = true;
                continue;
            }
            if (!open) {
                continue;
            }
            if (Tag.LEAVES.isTagged(material)) {
                free = 0;
                continue;
            }
            return free >= clearance && isSafeFloor(material) ? y + 1 : NONE;
        }
        return NONE;
    }

    public static int standY(PlayableArea area, int x, int z, int clearance, boolean allowWater) {
        return standY(area.world(), x, z, scanTop(area, x, z), area.minY(), clearance, allowWater);
    }

    /**
     * The topmost solid block of a column that has open space above it. Used for falling hazards
     * (TNT, meteors), which may land on leaves or water.
     *
     * @return the block Y, or {@link #NONE} if the column is empty void
     */
    public static int impactY(PlayableArea area, int x, int z) {
        World world = area.world();
        boolean seenOpen = false;
        for (int y = scanTop(area, x, z); y >= area.minY(); y--) {
            Material material = world.getType(x, y, z);
            if (material.isAir() || !material.isSolid()) {
                seenOpen = true;
                continue;
            }
            if (seenOpen) {
                return y;
            }
        }
        return NONE;
    }

    /** Number of open (air or non-solid) blocks directly above {@code y}, up to the area ceiling. */
    public static int openSpaceAbove(PlayableArea area, int x, int y, int z) {
        World world = area.world();
        int open = 0;
        for (int cy = y + 1; cy <= area.maxY(); cy++) {
            Material material = world.getType(x, cy, z);
            if (!material.isAir() && material.isSolid()) {
                break;
            }
            open++;
        }
        return open;
    }

    /**
     * Picks candidate columns. Small areas use every column; large areas (the main End island) sample
     * columns near players, only in loaded chunks.
     */
    public static List<int[]> candidateColumns(PlayableArea area, Random random, int maxSamples, int focusRadius) {
        List<int[]> columns = new ArrayList<>();
        World world = area.world();
        if (area.columnCount() <= maxSamples) {
            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    if (world.isChunkLoaded(x >> 4, z >> 4)) {
                        columns.add(new int[]{x, z});
                    }
                }
            }
            Collections.shuffle(columns, random);
            return columns;
        }

        List<Player> focus = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (!player.isDead() && area.contains(player.getLocation())) {
                focus.add(player);
            }
        }
        for (int i = 0; i < maxSamples; i++) {
            int x;
            int z;
            if (!focus.isEmpty()) {
                Location center = focus.get(random.nextInt(focus.size())).getLocation();
                x = area.clampX(center.getBlockX() + random.nextInt(focusRadius * 2 + 1) - focusRadius);
                z = area.clampZ(center.getBlockZ() + random.nextInt(focusRadius * 2 + 1) - focusRadius);
            } else {
                x = area.randomX(random);
                z = area.randomZ(random);
            }
            if (world.isChunkLoaded(x >> 4, z >> 4)) {
                columns.add(new int[]{x, z});
            }
        }
        return columns;
    }

    /**
     * Finds up to {@code count} distinct safe standing spots (block-centred feet locations).
     * May return fewer spots, or none at all; callers must handle that.
     */
    public static List<Location> findSpots(PlayableArea area, int count, int clearance, boolean allowWater,
                                           Random random, int focusRadius) {
        List<Location> spots = new ArrayList<>();
        for (int[] column : candidateColumns(area, random, 256, focusRadius)) {
            int y = standY(area, column[0], column[1], clearance, allowWater);
            if (y != NONE) {
                spots.add(new Location(area.world(), column[0] + 0.5, y, column[1] + 0.5));
                if (spots.size() >= count) {
                    break;
                }
            }
        }
        return spots;
    }

    /**
     * Finds the safe player spawn closest to a preferred point: solid, non-dangerous floor,
     * two free blocks, no water at the feet.
     *
     * @return the spawn, or {@code null} if the area has none
     */
    public static Location findPlayerSpawn(PlayableArea area, double preferX, double preferZ, double minDistance) {
        World world = area.world();
        List<int[]> columns = new ArrayList<>();
        // Large areas: only look around the preferred point instead of sorting every column of the island.
        int radius = 64;
        int fromX = Math.max(area.minX(), (int) Math.floor(preferX) - radius);
        int toX = Math.min(area.maxX(), (int) Math.floor(preferX) + radius);
        int fromZ = Math.max(area.minZ(), (int) Math.floor(preferZ) - radius);
        int toZ = Math.min(area.maxZ(), (int) Math.floor(preferZ) + radius);
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                double dx = x + 0.5 - preferX;
                double dz = z + 0.5 - preferZ;
                if (dx * dx + dz * dz >= minDistance * minDistance && world.isChunkLoaded(x >> 4, z >> 4)) {
                    columns.add(new int[]{x, z});
                }
            }
        }
        columns.sort(Comparator.comparingDouble(c -> {
            double dx = c[0] + 0.5 - preferX;
            double dz = c[1] + 0.5 - preferZ;
            return dx * dx + dz * dz;
        }));
        int checked = 0;
        for (int[] column : columns) {
            int y = standY(area, column[0], column[1], 2, false);
            if (y != NONE && !isWaterLike(world.getType(column[0], y, column[1]))) {
                return new Location(world, column[0] + 0.5, y, column[1] + 0.5);
            }
            if (++checked > 4096) {
                break;
            }
        }
        return null;
    }

    /**
     * Spawn search for cave dimensions (the Nether): instead of the highest pocket under the ceiling, find a floor
     * in the open cavern above {@code minY} with at least {@code minHeadroom} free blocks above the feet.
     *
     * @return the spawn, or {@code null} if no column qualifies
     */
    public static Location findCavernSpawn(PlayableArea area, int minY, int minHeadroom) {
        World world = area.world();
        List<int[]> columns = new ArrayList<>();
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                if (world.isChunkLoaded(x >> 4, z >> 4)) {
                    columns.add(new int[]{x, z});
                }
            }
        }
        double cx = area.centerX();
        double cz = area.centerZ();
        columns.sort(Comparator.comparingDouble(c -> (c[0] + 0.5 - cx) * (c[0] + 0.5 - cx) + (c[1] + 0.5 - cz) * (c[1] + 0.5 - cz)));
        int bottom = Math.max(area.minY() + 1, minY);
        for (int[] column : columns.subList(0, Math.min(columns.size(), 4096))) {
            int x = column[0];
            int z = column[1];
            for (int y = bottom; y <= area.maxY() - minHeadroom; y++) {
                if (!isSafeFloor(world.getType(x, y - 1, z))) {
                    continue;
                }
                int headroom = 0;
                while (headroom < minHeadroom && isPassable(world.getType(x, y + headroom, z), false)) {
                    headroom++;
                }
                if (headroom >= minHeadroom) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
    }

    /** Re-checks that a previously found spawn is still safe (floor intact, two free blocks, no fluid). */
    public static boolean isStillSafe(Location spawn) {
        World world = spawn.getWorld();
        if (world == null) {
            return false;
        }
        int x = spawn.getBlockX();
        int y = spawn.getBlockY();
        int z = spawn.getBlockZ();
        return isSafeFloor(world.getType(x, y - 1, z))
                && isPassable(world.getType(x, y, z), false)
                && isPassable(world.getType(x, y + 1, z), false);
    }

    /**
     * Last-resort spawn: builds a 3x3 floor and clears a 3x3x3 pocket above it. Only used when an area
     * has no safe standing spot at all (e.g. a chunk that is completely lava or solid).
     */
    public static Location buildEmergencyPlatform(PlayableArea area, Material floor) {
        World world = area.world();
        int cx = area.clampX((int) Math.floor(area.centerX()));
        int cz = area.clampZ((int) Math.floor(area.centerZ()));
        int highest = world.getHighestBlockYAt(cx, cz, HeightMap.MOTION_BLOCKING);
        int floorY;
        if (highest > area.minY() && highest + 4 <= area.maxY()) {
            // Open sky above: build on the very top (on a lake this becomes a raft on the water surface).
            floorY = highest;
        } else {
            // Ceiling-capped areas (Nether, Ancient City) or empty void: use the highest open floor instead.
            int surface = impactY(area, cx, cz);
            floorY = surface != NONE
                    ? Math.min(surface, area.maxY() - 4)
                    : Math.max(area.minY() + 1, Math.min(area.maxY() - 4, 64));
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = area.clampX(cx + dx);
                int z = area.clampZ(cz + dz);
                world.getBlockAt(x, floorY, z).setType(floor, false);
                for (int dy = 1; dy <= 3; dy++) {
                    world.getBlockAt(x, floorY + dy, z).setType(Material.AIR, false);
                }
            }
        }
        return new Location(world, cx + 0.5, floorY + 1, cz + 0.5);
    }
}
