package dev.chunkfun.world;

import java.nio.file.Path;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * One generated, temporary ChunkFun world. Environments are never reused: once a rotation leaves
 * them they are unloaded and deleted, and the next visit generates a fresh world from the same
 * generation configuration so resources can be farmed again.
 */
public final class Environment {

    public enum Phase {
        PREPARING,
        READY,
        ACTIVE,
        RETIRING,
        RETIRED
    }

    private final EnvironmentVariant variant;
    private final World world;
    private final MaskedVanillaGenerator generator;
    private final Path folder;
    private final long seed;
    private volatile Phase phase = Phase.PREPARING;
    private volatile PlayableArea area;
    private volatile Location spawn;
    private volatile String description = "";

    public Environment(EnvironmentVariant variant, World world, MaskedVanillaGenerator generator, Path folder, long seed) {
        this.variant = variant;
        this.world = world;
        this.generator = generator;
        this.folder = folder;
        this.seed = seed;
    }

    public EnvironmentVariant variant() {
        return this.variant;
    }

    public World world() {
        return this.world;
    }

    public MaskedVanillaGenerator generator() {
        return this.generator;
    }

    public Path folder() {
        return this.folder;
    }

    public long seed() {
        return this.seed;
    }

    public Phase phase() {
        return this.phase;
    }

    public void phase(Phase phase) {
        this.phase = phase;
    }

    public PlayableArea area() {
        return this.area;
    }

    public void area(PlayableArea area) {
        this.area = area;
    }

    /** A copy of the verified safe spawn location (safe to call from async spawn-location events). */
    public Location spawn() {
        Location current = this.spawn;
        return current == null ? null : current.clone();
    }

    public void spawn(Location spawn) {
        this.spawn = spawn.clone();
    }

    public String description() {
        return this.description;
    }

    public void description(String description) {
        this.description = description;
    }

    public boolean isUsable() {
        return (this.phase == Phase.READY || this.phase == Phase.ACTIVE) && this.spawn != null && this.area != null;
    }

    /** Whether the given world is this environment's world. */
    public boolean owns(World other) {
        return other != null && other.equals(this.world);
    }

    @Override
    public String toString() {
        return "Environment[" + this.variant + " " + this.world.getName() + " " + this.phase + "]";
    }
}
