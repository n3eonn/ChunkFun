package dev.chunkfun.events.special;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.world.Environment;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Low Gravity: every player in the environment gets strong Jump Boost and Slow Falling. The effects are
 * refreshed once per second with a short duration, so they wear off by themselves; when the event ends they
 * are removed right away.
 */
public final class LowGravityEvent extends SpecialEvent {

    private static final int EFFECT_TICKS = 50;

    private final Set<UUID> affected = new HashSet<>();

    public LowGravityEvent(ChunkFunPlugin plugin, Environment environment) {
        super(plugin, environment);
    }

    @Override
    public SpecialEventType type() {
        return SpecialEventType.LOW_GRAVITY;
    }

    @Override
    public void start() {
        int amplifier = this.settings().lowGravity().jumpBoostLevel() - 1;
        this.repeat(0L, 20L, () -> {
            for (Player player : this.environment.world().getPlayers()) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, EFFECT_TICKS, amplifier, true, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, EFFECT_TICKS, 0, true, false, true));
                this.affected.add(player.getUniqueId());
            }
        });
    }

    @Override
    protected void onStop(boolean immediate) {
        for (UUID id : this.affected) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.removePotionEffect(PotionEffectType.JUMP_BOOST);
                player.removePotionEffect(PotionEffectType.SLOW_FALLING);
            }
        }
        this.affected.clear();
    }
}
