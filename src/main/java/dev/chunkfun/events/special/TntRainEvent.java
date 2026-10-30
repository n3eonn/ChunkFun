package dev.chunkfun.events.special;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.world.Environment;
import dev.chunkfun.world.PlayableArea;
import dev.chunkfun.world.SurfaceScanner;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.util.Vector;

/**
 * Drops one primed TNT per interval (default: every second) above a random column of the playable area.
 * The TNT is an ordinary {@link TNTPrimed} entity, so it falls, explodes and destroys blocks like vanilla TNT.
 */
public final class TntRainEvent extends SpecialEvent {

    public TntRainEvent(ChunkFunPlugin plugin, Environment environment) {
        super(plugin, environment);
    }

    @Override
    public SpecialEventType type() {
        return SpecialEventType.TNT_RAIN;
    }

    @Override
    public void start() {
        this.repeat(0L, this.settings().tntRain().spawnIntervalTicks(), this::dropTnt);
    }

    private void dropTnt() {
        ChunkFunConfig.TntRainSettings settings = this.settings().tntRain();
        int[] column = this.randomImpactColumn();
        if (column == null) {
            return;
        }
        PlayableArea area = this.area();
        int x = column[0];
        int impact = column[1];
        int z = column[2];
        // Stay below ceilings (Nether roof, cave ceilings) so the TNT actually reaches the ground.
        int open = SurfaceScanner.openSpaceAbove(area, x, impact, z);
        int height = Math.max(1, Math.min(settings.dropHeight(), open - 1));
        World world = area.world();
        Location spawn = new Location(world, x + 0.5, impact + 1 + height, z + 0.5);
        world.spawn(spawn, TNTPrimed.class, tnt -> {
            tnt.setFuseTicks(settings.fuseTicks());
            tnt.setVelocity(new Vector(0.0, -0.1, 0.0));
        });
    }

    @Override
    protected void onStop(boolean immediate) {
        // Already-falling TNT behaves like normal TNT; only spawning stops.
    }
}
