package dev.chunkfun.events.special;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.world.Environment;
import dev.chunkfun.world.PlayableArea;
import dev.chunkfun.world.SurfaceScanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Base class for special events. Each event owns its repeating tasks; {@link #stop(boolean)} cancels
 * all of them and lets the event remove its entities or restore blocks.
 */
public abstract class SpecialEvent {

    protected final ChunkFunPlugin plugin;
    protected final Environment environment;
    protected final Random random = new Random();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean stopped;

    protected SpecialEvent(ChunkFunPlugin plugin, Environment environment) {
        this.plugin = plugin;
        this.environment = environment;
    }

    public abstract SpecialEventType type();

    /** Starts the event. Called once on the main thread. */
    public abstract void start();

    /**
     * Ends the event: cancels its tasks, then calls {@link #onStop(boolean)}.
     *
     * @param immediate true during plugin shutdown; cleanup must finish synchronously
     */
    public final void stop(boolean immediate) {
        if (this.stopped) {
            return;
        }
        this.stopped = true;
        for (BukkitTask task : this.tasks) {
            task.cancel();
        }
        this.tasks.clear();
        this.onStop(immediate);
    }

    protected abstract void onStop(boolean immediate);

    protected boolean isStopped() {
        return this.stopped;
    }

    protected ChunkFunConfig.SpecialEventSettings settings() {
        return this.plugin.settings().specialEvents();
    }

    protected PlayableArea area() {
        return this.environment.area();
    }

    /** Whether the environment is still the live, loaded world this event runs in. */
    protected boolean environmentAlive() {
        return this.environment.phase() == Environment.Phase.ACTIVE
                && Bukkit.getWorld(this.environment.world().getUID()) != null;
    }

    protected BukkitTask repeat(long delay, long period, Runnable action) {
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            if (!this.stopped && this.environmentAlive()) {
                action.run();
            }
        }, delay, period);
        this.tasks.add(task);
        return task;
    }

    /**
     * A random column inside the playable area that has ground (falling hazards need something to hit).
     *
     * @return {x, impactY, z} or null
     */
    protected int[] randomImpactColumn() {
        PlayableArea area = this.area();
        for (int[] column : SurfaceScanner.candidateColumns(area, this.random, 12, this.plugin.settings().largeAreaFocusRadius())) {
            int y = SurfaceScanner.impactY(area, column[0], column[1]);
            if (y != SurfaceScanner.NONE) {
                return new int[]{column[0], y, column[1]};
            }
        }
        return null;
    }
}
