package dev.chunkfun.events.special;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.util.Keys;
import dev.chunkfun.world.Environment;
import dev.chunkfun.world.SurfaceScanner;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Sheep;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Spawns aggressive event sheep that hunt and ram players. They are red, glowing, larger and named,
 * so they are easy to tell apart from normal sheep, and they are removed when the event ends.
 */
public final class SheepAdventureEvent extends SpecialEvent {

    private final List<Sheep> sheep = new ArrayList<>();
    private final NamespacedKey goalKey;

    public SheepAdventureEvent(ChunkFunPlugin plugin, Environment environment) {
        super(plugin, environment);
        this.goalKey = new NamespacedKey(plugin, "ram_player");
    }

    @Override
    public SpecialEventType type() {
        return SpecialEventType.SHEEP_ADVENTURE;
    }

    @Override
    public void start() {
        ChunkFunConfig.SheepSettings settings = this.settings().sheep();
        List<Location> spots = SurfaceScanner.findSpots(this.area(), settings.count(), 2, true, this.random,
                this.plugin.settings().largeAreaFocusRadius());
        if (spots.isEmpty()) {
            spots = List.of(this.environment.spawn());
        }
        for (int i = 0; i < settings.count(); i++) {
            Location spot = spots.get(i % spots.size());
            Sheep spawned = this.area().world().spawn(spot, Sheep.class, CreatureSpawnEvent.SpawnReason.CUSTOM,
                    entity -> this.configure(entity, settings));
            this.sheep.add(spawned);
        }
        // Drop references to sheep that died during the event so they can be garbage collected.
        this.repeat(100L, 100L, () -> this.sheep.removeIf(entity -> !entity.isValid()));
    }

    private void configure(Sheep entity, ChunkFunConfig.SheepSettings settings) {
        entity.setColor(DyeColor.RED);
        entity.setAdult();
        entity.customName(Component.text("Rampaging Ram", NamedTextColor.RED, TextDecoration.BOLD));
        entity.setCustomNameVisible(true);
        entity.setGlowing(true);
        entity.setRemoveWhenFarAway(false);
        entity.setPersistent(false);
        setBase(entity.getAttribute(Attribute.SCALE), settings.scale());
        setBase(entity.getAttribute(Attribute.MAX_HEALTH), settings.health());
        setBase(entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE), 0.4);
        entity.setHealth(Math.min(settings.health(), entity.getAttribute(Attribute.MAX_HEALTH) == null
                ? 8.0 : entity.getAttribute(Attribute.MAX_HEALTH).getValue()));
        Keys.markManaged(entity, "event_sheep");

        Bukkit.getMobGoals().removeAllGoals(entity);
        Bukkit.getMobGoals().addGoal(entity, 0, new RamPlayerGoal(this.goalKey, entity, settings));
    }

    private static void setBase(AttributeInstance attribute, double value) {
        if (attribute != null) {
            attribute.setBaseValue(value);
        }
    }

    @Override
    protected void onStop(boolean immediate) {
        for (Sheep entity : this.sheep) {
            if (entity.isValid()) {
                if (!immediate) {
                    entity.getWorld().spawnParticle(Particle.CLOUD, entity.getLocation().add(0.0, 0.6, 0.0), 12, 0.3, 0.3, 0.3, 0.02);
                }
                entity.remove();
            }
        }
        this.sheep.clear();
    }
}
