package dev.chunkfun.events.special;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.util.Keys;
import dev.chunkfun.world.Environment;
import dev.chunkfun.world.PlayableArea;
import dev.chunkfun.world.SurfaceScanner;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.FallingBlock;

/**
 * Anvil Rain: falling anvils drop onto random columns and hurt whoever they hit, like vanilla falling anvils.
 * Anvils that have not landed when the event ends are removed; landed ones stay as loot.
 */
public final class AnvilRainEvent extends SpecialEvent {

    private final List<FallingBlock> falling = new ArrayList<>();

    public AnvilRainEvent(ChunkFunPlugin plugin, Environment environment) {
        super(plugin, environment);
    }

    @Override
    public SpecialEventType type() {
        return SpecialEventType.ANVIL_RAIN;
    }

    @Override
    public void start() {
        this.repeat(0L, this.settings().anvilRain().spawnIntervalTicks(), this::drop);
        this.repeat(40L, 40L, () -> this.falling.removeIf(entity -> !entity.isValid()));
    }

    private void drop() {
        ChunkFunConfig.AnvilRainSettings settings = this.settings().anvilRain();
        int[] column = this.randomImpactColumn();
        if (column == null) {
            return;
        }
        PlayableArea area = this.area();
        int open = SurfaceScanner.openSpaceAbove(area, column[0], column[1], column[2]);
        int height = Math.max(2, Math.min(settings.dropHeight(), open - 1));
        World world = area.world();
        Location spawn = new Location(world, column[0] + 0.5, column[1] + 1 + height, column[2] + 0.5);
        FallingBlock anvil = world.spawn(spawn, FallingBlock.class, entity -> {
            entity.setBlockData(Material.ANVIL.createBlockData());
            entity.setHurtEntities(true);
            entity.setDamagePerBlock(settings.damagePerBlock());
            entity.setMaxDamage(settings.maxDamage());
            entity.setDropItem(true);
            Keys.markManaged(entity, "anvil_rain");
        });
        this.falling.add(anvil);
    }

    @Override
    protected void onStop(boolean immediate) {
        for (FallingBlock anvil : this.falling) {
            if (anvil.isValid()) {
                anvil.remove();
            }
        }
        this.falling.clear();
    }
}
