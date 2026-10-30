package dev.chunkfun.events.special;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.effects.Effects;
import dev.chunkfun.util.Text;
import dev.chunkfun.util.TimeFormat;
import dev.chunkfun.util.WeightedTable;
import dev.chunkfun.world.Environment;
import java.util.Random;
import java.util.logging.Level;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/**
 * Runs at most one special event at a time and owns its presentation: start title, dragon roar,
 * boss bar with the remaining time, and the end message.
 */
public final class SpecialEventManager {

    private final ChunkFunPlugin plugin;
    private final Random random = new Random();
    private SpecialEvent active;
    private BossBar bar;
    private BukkitTask ticker;
    private int remaining;
    private int duration;

    public SpecialEventManager(ChunkFunPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean startRandom(Environment environment) {
        WeightedTable<SpecialEventType> table = WeightedTable.of(this.plugin.settings().specialEvents().weights());
        return this.start(table.pick(this.random), environment);
    }

    public boolean start(SpecialEventType type, Environment environment) {
        if (this.active != null || environment == null || !environment.isUsable()) {
            return false;
        }
        SpecialEvent event = switch (type) {
            case TNT_RAIN -> new TntRainEvent(this.plugin, environment);
            case SHEEP_ADVENTURE -> new SheepAdventureEvent(this.plugin, environment);
            case FLOOD -> new FloodEvent(this.plugin, environment);
            case METEOR_ATTACK -> new MeteorEvent(this.plugin, environment);
            case LIGHTNING_STORM -> new LightningStormEvent(this.plugin, environment);
            case ANVIL_RAIN -> new AnvilRainEvent(this.plugin, environment);
            case LOW_GRAVITY -> new LowGravityEvent(this.plugin, environment);
        };
        this.duration = this.plugin.settings().specialEvents().durationSeconds();
        this.remaining = this.duration;
        this.active = event;

        this.bar = BossBar.bossBar(this.barName(type), 1.0f, type.barColor(), BossBar.Overlay.NOTCHED_10);
        Component title = Component.text(type.displayName(), type.color(), TextDecoration.BOLD);
        Component subtitle = Component.text(type.description(), NamedTextColor.GRAY);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(this.bar);
            player.showTitle(Text.title(title, subtitle, 5, 50, 15));
            player.playSound(Text.sound("entity.ender_dragon.growl", 1.0f, 1.0f));
            Effects.fireworkBurst(player.getLocation().add(0.0, 3.0, 0.0));
            Effects.burst(player.getLocation().add(0.0, 1.0, 0.0), Particle.ELECTRIC_SPARK, 30, 0.8, 0.2);
        }
        Text.broadcast(Text.prefixed(Component.text("Special event: ", NamedTextColor.GRAY)
                .append(Component.text(type.displayName(), type.color(), TextDecoration.BOLD))
                .append(Component.text(" for " + this.duration + " seconds!", NamedTextColor.GRAY))));

        try {
            event.start();
        } catch (RuntimeException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "Special event " + type + " failed to start", ex);
            this.stopActive(false);
            return false;
        }
        this.ticker = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 20L, 20L);
        return true;
    }

    private void tick() {
        if (this.active == null) {
            return;
        }
        this.remaining--;
        if (this.remaining <= 0) {
            this.stopActive(false);
            return;
        }
        this.bar.name(this.barName(this.active.type()));
        this.bar.progress(Math.max(0.0f, Math.min(1.0f, this.remaining / (float) this.duration)));
    }

    private Component barName(SpecialEventType type) {
        return Component.text(type.displayName(), type.color(), TextDecoration.BOLD)
                .append(Component.text("  " + TimeFormat.clock(this.remaining), NamedTextColor.WHITE));
    }

    /**
     * Ends the active event, if any.
     *
     * @param immediate true during plugin shutdown (cleanup runs synchronously)
     */
    public void stopActive(boolean immediate) {
        SpecialEvent event = this.active;
        if (event == null) {
            return;
        }
        this.active = null;
        if (this.ticker != null) {
            this.ticker.cancel();
            this.ticker = null;
        }
        try {
            event.stop(immediate);
        } catch (RuntimeException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "Error while stopping special event " + event.type(), ex);
        }
        if (this.bar != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.hideBossBar(this.bar);
            }
            this.bar = null;
        }
        if (!immediate) {
            Text.broadcast(Text.prefixed(Component.text(event.type().displayName(), event.type().color())
                    .append(Component.text(" is over.", NamedTextColor.GRAY))));
        }
    }

    public void onJoin(Player player) {
        if (this.bar != null) {
            player.showBossBar(this.bar);
        }
    }

    public void onQuit(Player player) {
        if (this.bar != null) {
            player.hideBossBar(this.bar);
        }
    }

    public boolean isActive() {
        return this.active != null;
    }

    public @Nullable SpecialEventType activeType() {
        return this.active == null ? null : this.active.type();
    }

    public int remainingSeconds() {
        return this.active == null ? 0 : Math.max(0, this.remaining);
    }

    /** Whether flood water is held (or being removed) in the given world; its water must not flow meanwhile. */
    public boolean isFlooding(World world) {
        return FloodEvent.isFlooding(world);
    }
}
