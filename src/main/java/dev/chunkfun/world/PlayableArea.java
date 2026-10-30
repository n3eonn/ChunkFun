package dev.chunkfun.world;

import java.util.Random;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * The block-aligned rectangle (inclusive) players may use, plus the vertical range that surface
 * scans should consider (the Nether excludes its bedrock roof).
 */
public final class PlayableArea {

    private final World world;
    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;
    private final int minY;
    private final int maxY;

    public PlayableArea(World world, int minX, int minZ, int maxX, int maxZ, int minY, int maxY) {
        this.world = world;
        this.minX = Math.min(minX, maxX);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxZ = Math.max(minZ, maxZ);
        this.minY = minY;
        this.maxY = maxY;
    }

    public static PlayableArea ofChunks(World world, GenerationMask mask, int minY, int maxY) {
        return new PlayableArea(world, mask.minChunkX() << 4, mask.minChunkZ() << 4,
                (mask.maxChunkX() << 4) + 15, (mask.maxChunkZ() << 4) + 15, minY, maxY);
    }

    public World world() {
        return this.world;
    }

    public int minX() {
        return this.minX;
    }

    public int minZ() {
        return this.minZ;
    }

    public int maxX() {
        return this.maxX;
    }

    public int maxZ() {
        return this.maxZ;
    }

    /** Lowest Y that scans consider. */
    public int minY() {
        return this.minY;
    }

    /** Highest Y that scans consider (inclusive). */
    public int maxY() {
        return this.maxY;
    }

    public int widthX() {
        return this.maxX - this.minX + 1;
    }

    public int widthZ() {
        return this.maxZ - this.minZ + 1;
    }

    public int columnCount() {
        return this.widthX() * this.widthZ();
    }

    public boolean isSingleChunk() {
        return (this.minX >> 4) == (this.maxX >> 4) && (this.minZ >> 4) == (this.maxZ >> 4);
    }

    public double centerX() {
        return (this.minX + this.maxX + 1) / 2.0;
    }

    public double centerZ() {
        return (this.minZ + this.maxZ + 1) / 2.0;
    }

    public boolean contains(int blockX, int blockZ) {
        return blockX >= this.minX && blockX <= this.maxX && blockZ >= this.minZ && blockZ <= this.maxZ;
    }

    public boolean contains(double x, double z) {
        return x >= this.minX && x < this.maxX + 1 && z >= this.minZ && z < this.maxZ + 1;
    }

    public boolean contains(Location location) {
        return location.getWorld() != null && location.getWorld().equals(this.world)
                && this.contains(location.getX(), location.getZ());
    }

    public int clampX(int x) {
        return Math.max(this.minX, Math.min(this.maxX, x));
    }

    public int clampZ(int z) {
        return Math.max(this.minZ, Math.min(this.maxZ, z));
    }

    public int randomX(Random random) {
        return this.minX + random.nextInt(this.widthX());
    }

    public int randomZ(Random random) {
        return this.minZ + random.nextInt(this.widthZ());
    }

    public int minChunkX() {
        return this.minX >> 4;
    }

    public int minChunkZ() {
        return this.minZ >> 4;
    }

    public int maxChunkX() {
        return this.maxX >> 4;
    }

    public int maxChunkZ() {
        return this.maxZ >> 4;
    }

    @Override
    public String toString() {
        return "PlayableArea[" + this.world.getName() + " x=" + this.minX + ".." + this.maxX
                + " z=" + this.minZ + ".." + this.maxZ + " y=" + this.minY + ".." + this.maxY + "]";
    }
}
