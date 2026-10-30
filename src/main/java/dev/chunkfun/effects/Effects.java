package dev.chunkfun.effects;

import dev.chunkfun.util.Keys;
import dev.chunkfun.util.Text;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

/**
 * Small visual-effect building blocks. Fireworks spawned here are tagged as managed so
 * {@link EffectsService} can cancel the damage vanilla firework explosions would deal.
 */
public final class Effects {

    private static final Color[] PALETTE = {
            Color.fromRGB(0xFFD700), Color.fromRGB(0xFF8C00), Color.fromRGB(0x00E5FF), Color.fromRGB(0xFF4FD8),
            Color.fromRGB(0x7CFF4F), Color.fromRGB(0xFFFFFF), Color.fromRGB(0xB04FFF)};

    private static volatile boolean fireworksEnabled = true;

    private Effects() {
    }

    public static void fireworksEnabled(boolean enabled) {
        fireworksEnabled = enabled;
    }

    /** A harmless firework that explodes instantly at the location. */
    public static void fireworkBurst(Location location) {
        World world = location.getWorld();
        if (world == null || !fireworksEnabled) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        FireworkEffect effect = FireworkEffect.builder()
                .with(random.nextBoolean() ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.STAR)
                .withColor(PALETTE[random.nextInt(PALETTE.length)], PALETTE[random.nextInt(PALETTE.length)])
                .withFade(PALETTE[random.nextInt(PALETTE.length)])
                .flicker(random.nextBoolean())
                .trail(true)
                .build();
        Firework firework = world.spawn(location, Firework.class, entity -> {
            FireworkMeta meta = entity.getFireworkMeta();
            meta.addEffect(effect);
            meta.setPower(0);
            entity.setFireworkMeta(meta);
            Keys.markManaged(entity, "effect_firework");
        });
        firework.detonate();
    }

    public static void burst(Location location, Particle particle, int count, double spread, double speed) {
        World world = location.getWorld();
        if (world != null) {
            world.spawnParticle(particle, location, count, spread, spread, spread, speed);
        }
    }

    /** A horizontal ring of particles around a player, visible to everyone nearby. */
    public static void ring(Player player, Particle particle, double radius, int points) {
        Location center = player.getLocation().add(0.0, 0.15, 0.0);
        World world = center.getWorld();
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0 * i / points;
            world.spawnParticle(particle, center.getX() + Math.cos(angle) * radius, center.getY(),
                    center.getZ() + Math.sin(angle) * radius, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** Played for every player that arrives in a new environment. */
    public static void arrival(Player player) {
        Location location = player.getLocation();
        burst(location.clone().add(0.0, 1.0, 0.0), Particle.TOTEM_OF_UNDYING, 60, 0.6, 0.35);
        ring(player, Particle.END_ROD, 1.6, 28);
        fireworkBurst(location.clone().add(0.0, 2.5, 0.0));
        player.playSound(Text.sound("block.beacon.power_select", 1.0f, 1.2f));
    }
}
