package dev.chunkfun.supply;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.effects.Effects;
import dev.chunkfun.util.Keys;
import dev.chunkfun.util.Text;
import dev.chunkfun.world.Environment;
import dev.chunkfun.world.PlayableArea;
import dev.chunkfun.world.SurfaceScanner;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/**
 * Item supplies as lucky blocks. Every 30-150 s (configurable, independent of special events) a batch of lucky
 * blocks lands on the active environment. A lucky block is a block that never generates naturally in ChunkFun's
 * worlds (default: raw gold block), marked by an animated hologram and a sparkle aura. Breaking it runs a random
 * outcome: nerfed loot, a harmless surprise or a trap.
 *
 * <p>Lucky blocks are tracked by position, so the same block type placed by players is not lucky. Explosions
 * and pistons cannot destroy or move them.</p>
 */
public final class SupplyManager implements Listener {

    private static final NamedTextColor[] HOLOGRAM_COLORS = {
            NamedTextColor.GOLD, NamedTextColor.YELLOW, NamedTextColor.GREEN, NamedTextColor.AQUA,
            NamedTextColor.LIGHT_PURPLE, NamedTextColor.RED};

    private static final class LuckyBlock {
        final UUID worldId;
        final int x;
        final int y;
        final int z;
        final SupplyCategory category;
        final SupplyTier tier;
        final @Nullable TextDisplay hologram;

        LuckyBlock(Block block, SupplyCategory category, SupplyTier tier, @Nullable TextDisplay hologram) {
            this.worldId = block.getWorld().getUID();
            this.x = block.getX();
            this.y = block.getY();
            this.z = block.getZ();
            this.category = category;
            this.tier = tier;
            this.hologram = hologram;
        }

        boolean is(Block block) {
            return block.getX() == this.x && block.getY() == this.y && block.getZ() == this.z
                    && block.getWorld().getUID().equals(this.worldId);
        }

        void removeHologram() {
            if (this.hologram != null && this.hologram.isValid()) {
                this.hologram.remove();
            }
        }
    }

    private final ChunkFunPlugin plugin;
    private final SupplyLoot loot;
    private final LuckyBlockOpener opener;
    private final Random random = new Random();
    private final List<LuckyBlock> blocks = new ArrayList<>();
    private BukkitTask animator;
    private int animationStep;

    public SupplyManager(ChunkFunPlugin plugin) {
        this.plugin = plugin;
        this.loot = new SupplyLoot(plugin.getLogger());
        this.opener = new LuckyBlockOpener(plugin, this.loot);
    }

    public void start() {
        if (this.animator == null) {
            this.animator = Bukkit.getScheduler().runTaskTimer(this.plugin, this::animate, 10L, 10L);
        }
    }

    /** Drops a batch of lucky blocks; each rolls its own category and tier. */
    public void spawnSupply(Environment environment) {
        this.spawnSupply(environment, null);
    }

    /**
     * Drops a batch of lucky blocks.
     *
     * @param forcedTier loot tier for every block, or {@code null} to roll one per block
     */
    public void spawnSupply(Environment environment, @Nullable SupplyTier forcedTier) {
        if (environment == null || !environment.isUsable()) {
            return;
        }
        try {
            int placed = this.place(environment, forcedTier);
            if (placed > 0) {
                this.announce(placed);
            } else {
                this.plugin.getLogger().warning("No safe location for lucky blocks in " + environment.world().getName());
            }
        } catch (RuntimeException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "Lucky block drop failed", ex);
        }
    }

    private int place(Environment environment, @Nullable SupplyTier forcedTier) {
        ChunkFunConfig.SupplySettings settings = this.plugin.settings().supply();
        SupplyCategory[] categories = SupplyCategory.values();
        int placed = 0;
        for (Block block : this.pickSpots(environment.area(), settings.luckyBlockCount())) {
            SupplyCategory category = categories[this.random.nextInt(categories.length)];
            SupplyTier tier = forcedTier != null ? forcedTier : settings.tiers().pick(this.random);
            block.setType(settings.blockType(), false);
            TextDisplay hologram = settings.holograms() ? this.spawnHologram(block) : null;
            this.blocks.add(new LuckyBlock(block, category, tier, hologram));
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            Effects.burst(center, Particle.END_ROD, 12, 0.4, 0.05);
            if (placed % 3 == 0) {
                Effects.fireworkBurst(center.clone().add(0.0, 2.0, 0.0));
            }
            placed++;
        }
        return placed;
    }

    private TextDisplay spawnHologram(Block block) {
        return block.getWorld().spawn(block.getLocation().add(0.5, 1.45, 0.5), TextDisplay.class, entity -> {
            entity.text(hologramText(NamedTextColor.GOLD));
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setShadowed(true);
            entity.setPersistent(false);
            Keys.markManaged(entity, "lucky_hologram");
        });
    }

    private static Component hologramText(NamedTextColor color) {
        return Component.text("? LUCKY BLOCK ?", color, TextDecoration.BOLD)
                .appendNewline()
                .append(Component.text("Break me!", NamedTextColor.WHITE));
    }

    /**
     * Picks distinct safe surface positions (a solid floor and room above), preferring spots two blocks apart.
     * If the area is crowded, blocks are stacked on top of already chosen ones.
     */
    private List<Block> pickSpots(PlayableArea area, int count) {
        World world = area.world();
        List<Location> candidates = SurfaceScanner.findSpots(area, Math.max(count * 4, 64), 2, true, this.random,
                this.plugin.settings().largeAreaFocusRadius());
        List<Block> chosen = new ArrayList<>();
        for (int spacing : new int[]{2, 1, 0}) {
            for (Location candidate : candidates) {
                if (chosen.size() >= count) {
                    break;
                }
                Block block = candidate.getBlock();
                boolean free = true;
                for (Block other : chosen) {
                    if (other.equals(block) || (spacing > 0 && Math.abs(other.getX() - block.getX()) <= spacing
                            && Math.abs(other.getZ() - block.getZ()) <= spacing)) {
                        free = false;
                        break;
                    }
                }
                if (free) {
                    chosen.add(block);
                }
            }
        }
        int index = 0;
        while (chosen.size() < count && !chosen.isEmpty() && index < chosen.size() * 4) {
            Block above = chosen.get(index % chosen.size()).getRelative(0, 1, 0);
            while (chosen.contains(above)) {
                above = above.getRelative(0, 1, 0);
            }
            if (above.getY() + 1 <= area.maxY() && isReplaceable(above.getType())
                    && isReplaceable(world.getType(above.getX(), above.getY() + 1, above.getZ()))) {
                chosen.add(above);
            }
            index++;
        }
        return chosen;
    }

    private static boolean isReplaceable(Material material) {
        return material.isAir() || material == Material.WATER;
    }

    private void announce(int placed) {
        Text.broadcast(Text.prefixed(Component.text("Lucky blocks! ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(placed + " lucky blocks have landed. Break them - if you dare!", NamedTextColor.YELLOW))));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendActionBar(Component.text("Lucky blocks have landed!", NamedTextColor.GOLD));
            player.playSound(Text.sound("entity.firework_rocket.blast", 1.0f, 1.0f));
            player.playSound(Text.sound("block.amethyst_block.resonate", 1.0f, 1.4f));
        }
    }

    /** Hologram colour cycle and sparkle aura; also forgets blocks that disappeared some other way. */
    private void animate() {
        this.animationStep++;
        NamedTextColor color = HOLOGRAM_COLORS[this.animationStep % HOLOGRAM_COLORS.length];
        Material type = this.plugin.settings().supply().blockType();
        Iterator<LuckyBlock> iterator = this.blocks.iterator();
        while (iterator.hasNext()) {
            LuckyBlock lucky = iterator.next();
            World world = Bukkit.getWorld(lucky.worldId);
            if (world == null || !world.isChunkLoaded(lucky.x >> 4, lucky.z >> 4)) {
                if (world == null) {
                    iterator.remove();
                }
                continue;
            }
            if (world.getType(lucky.x, lucky.y, lucky.z) != type) {
                lucky.removeHologram();
                iterator.remove();
                continue;
            }
            if (lucky.hologram != null && lucky.hologram.isValid()) {
                lucky.hologram.text(hologramText(color));
            }
            world.spawnParticle(Particle.WAX_ON, lucky.x + 0.5, lucky.y + 0.5, lucky.z + 0.5, 3, 0.45, 0.45, 0.45, 0.0);
            if (this.animationStep % 2 == 0) {
                world.spawnParticle(Particle.ELECTRIC_SPARK, lucky.x + 0.5, lucky.y + 1.1, lucky.z + 0.5, 2, 0.3, 0.1, 0.3, 0.0);
            }
        }
    }

    private @Nullable LuckyBlock find(Block block) {
        for (LuckyBlock lucky : this.blocks) {
            if (lucky.is(block)) {
                return lucky;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (this.blocks.isEmpty()) {
            return;
        }
        Block block = event.getBlock();
        LuckyBlock lucky = this.find(block);
        if (lucky == null) {
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        this.blocks.remove(lucky);
        lucky.removeHologram();
        Player player = event.getPlayer();
        Location location = block.getLocation();
        // Next tick, so the block is gone before loot drops or mobs spawn in its place.
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline()) {
                this.opener.open(player, location, lucky.category, lucky.tier);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!this.blocks.isEmpty()) {
            event.blockList().removeIf(block -> this.find(block) != null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!this.blocks.isEmpty()) {
            event.blockList().removeIf(block -> this.find(block) != null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> this.find(block) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> this.find(block) != null)) {
            event.setCancelled(true);
        }
    }

    /** Forgets the lucky blocks of an environment that is being retired (its world will be deleted). */
    public void forgetEnvironment(Environment environment) {
        UUID worldId = environment.world().getUID();
        Iterator<LuckyBlock> iterator = this.blocks.iterator();
        while (iterator.hasNext()) {
            LuckyBlock lucky = iterator.next();
            if (lucky.worldId.equals(worldId)) {
                lucky.removeHologram();
                iterator.remove();
            }
        }
    }

    public void clearAll() {
        for (LuckyBlock lucky : this.blocks) {
            lucky.removeHologram();
        }
        this.blocks.clear();
    }

    public SupplyLoot loot() {
        return this.loot;
    }
}
