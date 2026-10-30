package dev.chunkfun.events.special;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.world.Environment;
import dev.chunkfun.world.PlayableArea;
import dev.chunkfun.world.SurfaceScanner;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import dev.chunkfun.util.BlockKeys;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * The Flood: water rises layer by layer through the playable area, then recedes when the event ends.
 *
 * <ul>
 *     <li>Only <em>air</em> is replaced, so no terrain, container or player build is destroyed.</li>
 *     <li>Every placed water block is recorded; when the event ends exactly those positions are turned back
 *     into air if they still hold water. Anything a player put there in the meantime stays.</li>
 *     <li>Water is placed without physics updates, and water flow is cancelled in the flooded world while a
 *     flood is active, so it never spreads outside the area or leaves flowing water behind.</li>
 *     <li>Placement and restoration are spread over ticks with a block budget.</li>
 *     <li>For very large areas (the main End island) only columns near players are flooded, and the depth is
 *     limited so the total number of blocks stays below {@code flood.max-blocks}.</li>
 * </ul>
 */
public final class FloodEvent extends SpecialEvent {

    /** Worlds whose water must not flow (flood running or restoring). Main thread only. */
    private static final Set<UUID> FLOODED_WORLDS = new HashSet<>();

    private final List<int[]> columns = new ArrayList<>();
    private final List<Integer> columnBottoms = new ArrayList<>();
    /** Positions that received flood water, packed with {@link BlockKeys}. */
    private final LongArrayList placed = new LongArrayList();
    private final BlockData water = Material.WATER.createBlockData();
    private int floodBottom;
    private int floodTop;
    private int layer;
    private int columnCursor;
    private int elapsedTicks;
    private int riseTicks;

    public FloodEvent(ChunkFunPlugin plugin, Environment environment) {
        super(plugin, environment);
    }

    public static boolean isFlooding(World world) {
        return world != null && FLOODED_WORLDS.contains(world.getUID());
    }

    @Override
    public SpecialEventType type() {
        return SpecialEventType.FLOOD;
    }

    @Override
    public void start() {
        ChunkFunConfig.FloodSettings settings = this.settings().flood();
        PlayableArea area = this.area();
        World world = area.world();
        this.collectColumns(area);
        if (this.columns.isEmpty()) {
            return;
        }
        // Raise the bottom if the full volume would exceed the block budget.
        int depthLimit = Math.max(1, settings.maxBlocks() / this.columns.size());
        this.floodBottom = Math.max(this.floodBottom, this.floodTop - depthLimit + 1);
        this.layer = this.floodBottom;
        this.riseTicks = Math.max(1, settings.riseSeconds() * 20);
        FLOODED_WORLDS.add(world.getUID());
        this.repeat(1L, 1L, this::rise);
    }

    /**
     * Reads chunk snapshots to find the columns to flood: every column that has ground. The flood top is the
     * average floor height plus the configured margin; the bottom is the lowest ground block.
     */
    private void collectColumns(PlayableArea area) {
        World world = area.world();
        Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
        List<int[]> candidates = new ArrayList<>();
        int focus = this.plugin.settings().largeAreaFocusRadius();
        if (area.columnCount() <= 4096) {
            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    candidates.add(new int[]{x, z});
                }
            }
        } else {
            Set<Long> seen = new HashSet<>();
            for (Player player : world.getPlayers()) {
                if (!area.contains(player.getLocation())) {
                    continue;
                }
                Location location = player.getLocation();
                for (int x = location.getBlockX() - focus; x <= location.getBlockX() + focus; x++) {
                    for (int z = location.getBlockZ() - focus; z <= location.getBlockZ() + focus; z++) {
                        if (area.contains(x, z) && seen.add(((long) x << 32) ^ (z & 0xFFFFFFFFL)) && seen.size() <= 16384) {
                            candidates.add(new int[]{x, z});
                        }
                    }
                }
            }
        }

        long floorSum = 0;
        int floorCount = 0;
        int lowest = Integer.MAX_VALUE;
        for (int[] column : candidates) {
            int chunkX = column[0] >> 4;
            int chunkZ = column[1] >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                continue;
            }
            ChunkSnapshot snapshot = snapshots.computeIfAbsent(((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL), key -> {
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                return chunk.getChunkSnapshot(true, false, false);
            });
            int localX = column[0] & 15;
            int localZ = column[1] & 15;
            int bottom = Integer.MIN_VALUE;
            for (int y = area.minY(); y <= area.maxY(); y++) {
                if (!snapshot.getBlockType(localX, y, localZ).isAir()) {
                    bottom = y;
                    break;
                }
            }
            if (bottom == Integer.MIN_VALUE) {
                continue; // Empty void column: never hang water in mid-air.
            }
            int top = Math.min(area.maxY(), snapshot.getHighestBlockYAt(localX, localZ) + 1);
            int floor = floorFromSnapshot(snapshot, localX, localZ, top, area.minY());
            if (floor != SurfaceScanner.NONE) {
                floorSum += floor;
                floorCount++;
            }
            this.columns.add(column);
            this.columnBottoms.add(bottom);
            lowest = Math.min(lowest, bottom);
        }
        if (this.columns.isEmpty()) {
            return;
        }
        int averageFloor = floorCount == 0 ? lowest : (int) Math.round(floorSum / (double) floorCount);
        this.floodTop = Math.min(area.maxY(), averageFloor + this.settings().flood().heightAboveSurface());
        this.floodBottom = lowest;
    }

    /** Same rule as {@link SurfaceScanner#standY}, applied to a snapshot. */
    private static int floorFromSnapshot(ChunkSnapshot snapshot, int x, int z, int top, int bottom) {
        int free = 0;
        for (int y = top; y >= bottom; y--) {
            Material material = snapshot.getBlockType(x, y, z);
            if (SurfaceScanner.isPassable(material, true)) {
                free++;
                continue;
            }
            if (free >= 2 && SurfaceScanner.isSafeFloor(material)) {
                return y;
            }
            free = 0;
        }
        return SurfaceScanner.NONE;
    }

    private void rise() {
        this.elapsedTicks++;
        int target = this.floodBottom + (int) Math.floor((this.floodTop - this.floodBottom)
                * Math.min(1.0, this.elapsedTicks / (double) this.riseTicks));
        int budget = this.settings().flood().blocksPerTick();
        World world = this.area().world();
        while (budget > 0 && this.layer <= target && this.layer <= this.floodTop) {
            int[] column = this.columns.get(this.columnCursor);
            if (this.layer >= this.columnBottoms.get(this.columnCursor)) {
                int x = column[0];
                int z = column[1];
                if (world.getType(x, this.layer, z).isAir()) {
                    world.getBlockAt(x, this.layer, z).setBlockData(this.water, false);
                    this.placed.add(BlockKeys.pack(x, this.layer, z));
                }
            }
            budget--;
            this.columnCursor++;
            if (this.columnCursor >= this.columns.size()) {
                this.columnCursor = 0;
                this.layer++;
            }
        }
    }

    @Override
    protected void onStop(boolean immediate) {
        World world = this.environment.world();
        if (this.placed.isEmpty() || Bukkit.getWorld(world.getUID()) == null
                || this.environment.phase() == Environment.Phase.RETIRED) {
            FLOODED_WORLDS.remove(world.getUID());
            return;
        }
        if (immediate) {
            this.restore(world, Integer.MAX_VALUE);
            FLOODED_WORLDS.remove(world.getUID());
            return;
        }
        int budget = this.settings().flood().restoreBlocksPerTick();
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            if (Bukkit.getWorld(world.getUID()) == null || this.restore(world, budget)) {
                FLOODED_WORLDS.remove(world.getUID());
                holder[0].cancel();
            }
        }, 1L, 1L);
    }

    /** Turns recorded flood water back into air. Returns true once everything is restored. */
    private boolean restore(World world, int budget) {
        int processed = 0;
        while (!this.placed.isEmpty() && processed < budget) {
            long key = this.placed.removeLong(this.placed.size() - 1);
            int x = BlockKeys.x(key);
            int y = BlockKeys.y(key);
            int z = BlockKeys.z(key);
            if (world.getType(x, y, z) == Material.WATER) {
                world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
            processed++;
        }
        return this.placed.isEmpty();
    }
}
