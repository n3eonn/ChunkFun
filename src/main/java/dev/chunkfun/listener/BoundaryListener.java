package dev.chunkfun.listener;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.world.Environment;
import dev.chunkfun.world.PlayableArea;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Boundary rules for ChunkFun worlds.
 *
 * <p>Always: portals never lead out of an environment, and fluids never pour from the edge of the area down
 * into the void (endless falls would keep the fluid ticker busy).</p>
 *
 * <p>Only with {@code game.world-border: true}: blocks, pistons and teleports (ender pearls, chorus fruit,
 * endermen, shulkers) are confined to the playable area as well. With the border off (the default), players
 * may fly out of the chunk and build in the void around it.</p>
 */
public final class BoundaryListener implements Listener {

    private final ChunkFunPlugin plugin;

    public BoundaryListener(ChunkFunPlugin plugin) {
        this.plugin = plugin;
    }

    /** The area of the active environment if the world belongs to it. */
    private @Nullable PlayableArea activeArea(World world) {
        Environment active = this.plugin.game().activeEnvironment();
        if (active == null || !active.owns(world)) {
            return null;
        }
        return active.area();
    }

    /** The active area, but only when the world border confines players to it. */
    private @Nullable PlayableArea restrictedArea(World world) {
        return this.plugin.settings().worldBorder() ? this.activeArea(world) : null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        World world = event.getBlock().getWorld();
        if (!ChunkFunPlugin.isChunkFunWorld(world)) {
            return;
        }
        Block to = event.getToBlock();
        PlayableArea area = this.activeArea(world);
        if (area != null && !area.contains(to.getX(), to.getZ())) {
            // Outside the area fluids may spread over player-built floors, but never fall into the void.
            if (this.plugin.settings().worldBorder() || to.getRelative(BlockFace.DOWN).getType().isAir()) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getBlock().getType() == Material.WATER && this.plugin.specialEvents().isFlooding(world)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        PlayableArea area = this.restrictedArea(block.getWorld());
        if (area != null && !area.contains(block.getX(), block.getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        PlayableArea area = this.restrictedArea(event.getBlock().getWorld());
        if (area == null) {
            return;
        }
        BlockFace direction = event.getDirection();
        Block head = event.getBlock().getRelative(direction);
        if (!area.contains(head.getX(), head.getZ())) {
            event.setCancelled(true);
            return;
        }
        for (Block moved : event.getBlocks()) {
            Block target = moved.getRelative(direction);
            if (!area.contains(target.getX(), target.getZ())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        PlayableArea area = this.restrictedArea(event.getBlock().getWorld());
        if (area == null) {
            return;
        }
        for (Block moved : event.getBlocks()) {
            if (!area.contains(moved.getX(), moved.getZ())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Environments are isolated: nether portals, end portals and gateways never lead anywhere. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (ChunkFunPlugin.isChunkFunWorld(event.getFrom().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (ChunkFunPlugin.isChunkFunWorld(event.getFrom().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (ChunkFunPlugin.isChunkFunWorld(event.getWorld())
                && (event.getReason() == PortalCreateEvent.CreateReason.FIRE
                || event.getReason() == PortalCreateEvent.CreateReason.NETHER_PAIR)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        if (!ChunkFunPlugin.isChunkFunWorld(from.getWorld())) {
            return;
        }
        switch (event.getCause()) {
            case NETHER_PORTAL, END_PORTAL, END_GATEWAY -> {
                event.setCancelled(true);
                return;
            }
            default -> {
            }
        }
        Location to = event.getTo();
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN
                || event.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND) {
            return;
        }
        PlayableArea area = this.restrictedArea(from.getWorld());
        if (area != null && to.getWorld() != null && to.getWorld().equals(from.getWorld()) && !area.contains(to)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || !ChunkFunPlugin.isChunkFunWorld(from.getWorld())) {
            return;
        }
        PlayableArea area = this.restrictedArea(from.getWorld());
        if (area != null && to.getWorld() != null && to.getWorld().equals(from.getWorld()) && !area.contains(to)) {
            event.setCancelled(true);
        }
    }
}
