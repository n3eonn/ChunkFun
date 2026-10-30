package dev.chunkfun.events.special;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.world.Environment;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Lightning Storm: real lightning bolts strike random spots of the playable area. They damage players and
 * mobs, set fire and turn pigs, villagers and creepers into their lightning variants like vanilla lightning.
 */
public final class LightningStormEvent extends SpecialEvent {

    public LightningStormEvent(ChunkFunPlugin plugin, Environment environment) {
        super(plugin, environment);
    }

    @Override
    public SpecialEventType type() {
        return SpecialEventType.LIGHTNING_STORM;
    }

    @Override
    public void start() {
        ChunkFunConfig.LightningSettings settings = this.settings().lightning();
        this.repeat(10L, settings.strikeIntervalTicks(), () -> {
            for (int i = 0; i < settings.strikesPerInterval(); i++) {
                this.strike();
            }
        });
    }

    private void strike() {
        int[] column = this.randomImpactColumn();
        if (column == null) {
            return;
        }
        World world = this.area().world();
        world.strikeLightning(new Location(world, column[0] + 0.5, column[1] + 1, column[2] + 0.5));
    }

    @Override
    protected void onStop(boolean immediate) {
        // Lightning bolts are short-lived vanilla entities; nothing to clean up.
    }
}
