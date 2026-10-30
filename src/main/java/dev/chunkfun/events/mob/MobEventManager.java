package dev.chunkfun.events.mob;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.effects.Effects;
import dev.chunkfun.util.Text;
import dev.chunkfun.world.Environment;
import dev.chunkfun.world.PlayableArea;
import dev.chunkfun.world.SurfaceScanner;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Mob events: picks one mob type and one amount from the configured tables, then spawns that many of
 * that exact type on safe surface spots of the active chunk.
 */
public final class MobEventManager {

    /** Free blocks needed above the floor for tall mobs; everything else needs two. */
    private static final Map<EntityType, Integer> CLEARANCE = new EnumMap<>(EntityType.class);

    static {
        CLEARANCE.put(EntityType.WITHER, 4);
        CLEARANCE.put(EntityType.WARDEN, 3);
        CLEARANCE.put(EntityType.ENDERMAN, 3);
        CLEARANCE.put(EntityType.IRON_GOLEM, 3);
        CLEARANCE.put(EntityType.RAVAGER, 3);
        CLEARANCE.put(EntityType.GHAST, 4);
        CLEARANCE.put(EntityType.CHICKEN, 1);
    }

    private final ChunkFunPlugin plugin;
    private final Random random = new Random();

    public MobEventManager(ChunkFunPlugin plugin) {
        this.plugin = plugin;
    }

    public void trigger(Environment environment) {
        if (environment == null || !environment.isUsable()) {
            return;
        }
        ChunkFunConfig.MobEventSettings settings = this.plugin.settings().mobEvents();
        EntityType type = settings.mobs().pick(this.random);
        int amount = settings.amounts().pick(this.random);
        try {
            int spawned = this.spawn(environment, type, amount);
            this.announce(type, spawned);
        } catch (RuntimeException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "Mob event (" + amount + "x " + type + ") failed", ex);
        }
    }

    private int spawn(Environment environment, EntityType type, int amount) {
        PlayableArea area = environment.area();
        World world = area.world();
        int clearance = CLEARANCE.getOrDefault(type, 2);
        int focus = this.plugin.settings().largeAreaFocusRadius();

        List<Location> spots = SurfaceScanner.findSpots(area, amount, clearance, true, this.random, focus);
        if (spots.isEmpty() && clearance > 2) {
            // Not enough head room anywhere for this mob; fall back to normal two-block spots.
            spots = SurfaceScanner.findSpots(area, amount, 2, true, this.random, focus);
        }
        if (spots.isEmpty()) {
            // The verified player spawn is always safe to stand on.
            spots = List.of(environment.spawn());
        }
        if (spots.size() < amount) {
            this.plugin.getLogger().fine("Only " + spots.size() + " safe spots for " + amount + " " + type
                    + "; spots are shared.");
        }

        Class<? extends Entity> entityClass = type.getEntityClass();
        if (entityClass == null) {
            return 0;
        }
        List<LivingEntity> spawned = new ArrayList<>(amount);
        for (int i = 0; i < amount; i++) {
            Location spot = spots.get(i % spots.size()).clone();
            spot.setYaw(this.random.nextFloat() * 360.0f - 180.0f);
            Entity entity = world.spawn(spot, entityClass, CreatureSpawnEvent.SpawnReason.CUSTOM, created -> {
                if (created instanceof Mob mob) {
                    mob.setRemoveWhenFarAway(false);
                }
            });
            if (entity instanceof LivingEntity living && entity.isValid()) {
                spawned.add(living);
                Effects.burst(living.getLocation().add(0.0, 0.8, 0.0), Particle.SOUL, 12, 0.4, 0.03);
                Effects.burst(living.getLocation().add(0.0, 0.5, 0.0), Particle.LARGE_SMOKE, 8, 0.3, 0.02);
            }
        }
        // One tick later, move anything that ended up inside a block (e.g. another plugin changed the terrain).
        Bukkit.getScheduler().runTask(this.plugin, () -> this.unstick(environment, spawned));
        return spawned.size();
    }

    private void unstick(Environment environment, List<LivingEntity> entities) {
        if (!environment.isUsable()) {
            return;
        }
        for (LivingEntity entity : entities) {
            if (!entity.isValid()) {
                continue;
            }
            Material feet = entity.getLocation().getBlock().getType();
            Material eyes = entity.getEyeLocation().getBlock().getType();
            if (feet.isOccluding() || eyes.isOccluding()) {
                entity.teleport(environment.spawn());
            }
        }
    }

    private void announce(EntityType type, int amount) {
        if (amount <= 0) {
            return;
        }
        Component mob = Component.translatable(type, NamedTextColor.RED);
        Component message = Text.prefixed(Component.text("Mob event! ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(amount + "x ", NamedTextColor.YELLOW))
                .append(mob)
                .append(Component.text(amount == 1 ? " has appeared!" : " have appeared!", NamedTextColor.YELLOW)));
        Text.broadcast(message);
        Component actionBar = Component.text("Mob Event: ", NamedTextColor.GOLD)
                .append(Component.text(amount + "x ", NamedTextColor.YELLOW)).append(mob);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendActionBar(actionBar);
            player.playSound(Text.sound("entity.evoker.prepare_summon", 1.0f, 1.0f));
        }
    }
}
