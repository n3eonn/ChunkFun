package dev.chunkfun.effects;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.util.Keys;
import dev.chunkfun.world.Environment;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Game-wide visuals: every player in the game glows (the outline colour comes from the scoreboard team set up
 * by {@code ScoreboardService}), and each environment has its own ambient particles around players. Ambient
 * particles are sent per player, so nobody receives particles for other players' surroundings.
 */
public final class EffectsService implements Listener {

    private final ChunkFunPlugin plugin;
    private final Set<UUID> glowing = new HashSet<>();
    private BukkitTask glowTask;
    private BukkitTask ambientTask;

    public EffectsService(ChunkFunPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        this.stop();
        ChunkFunConfig.EffectsSettings settings = this.plugin.settings().effects();
        Effects.fireworksEnabled(settings.fireworks());
        this.glowTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::updateGlow, 20L, 20L);
        if (settings.ambientParticles()) {
            this.ambientTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::ambient, 40L, 10L);
        }
    }

    public void stop() {
        if (this.glowTask != null) {
            this.glowTask.cancel();
            this.glowTask = null;
        }
        if (this.ambientTask != null) {
            this.ambientTask.cancel();
            this.ambientTask = null;
        }
        this.clearGlow();
    }

    /** Players in the active environment glow; everybody else has the glow removed again. */
    private void updateGlow() {
        Environment active = this.plugin.game().activeEnvironment();
        boolean enabled = this.plugin.settings().effects().glowing() && this.plugin.game().isRunning() && active != null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean shouldGlow = enabled && active.owns(player.getWorld());
            if (shouldGlow) {
                if (!player.isGlowing()) {
                    player.setGlowing(true);
                }
                this.glowing.add(player.getUniqueId());
            } else if (this.glowing.remove(player.getUniqueId())) {
                player.setGlowing(false);
            }
        }
        // Forget players that went offline.
        this.glowing.removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void clearGlow() {
        Iterator<UUID> iterator = this.glowing.iterator();
        while (iterator.hasNext()) {
            Player player = Bukkit.getPlayer(iterator.next());
            if (player != null) {
                player.setGlowing(false);
            }
            iterator.remove();
        }
    }

    private void ambient() {
        Environment active = this.plugin.game().activeEnvironment();
        if (active == null || !this.plugin.game().isRunning()) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Player player : active.world().getPlayers()) {
            Location around = player.getLocation().add(random.nextDouble(-6, 6), random.nextDouble(0, 4), random.nextDouble(-6, 6));
            switch (active.variant()) {
                case OVERWORLD -> player.spawnParticle(Particle.SPORE_BLOSSOM_AIR, around, 6, 4.0, 2.0, 4.0, 0.0);
                case NETHER -> {
                    player.spawnParticle(Particle.ASH, around, 10, 5.0, 3.0, 5.0, 0.0);
                    player.spawnParticle(Particle.CRIMSON_SPORE, around, 4, 5.0, 3.0, 5.0, 0.0);
                }
                case END_CITY, END_ISLAND -> {
                    player.spawnParticle(Particle.REVERSE_PORTAL, around, 8, 5.0, 3.0, 5.0, 0.02);
                    player.spawnParticle(Particle.END_ROD, around, 1, 5.0, 3.0, 5.0, 0.01);
                }
                case ANCIENT_CITY -> player.spawnParticle(Particle.SCULK_SOUL, around, 3, 5.0, 2.0, 5.0, 0.01);
            }
        }
    }

    /** Effect fireworks are pure decoration: they never hurt anyone. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework && Keys.isManaged(firework)) {
            event.setCancelled(true);
        }
    }
}
