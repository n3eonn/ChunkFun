package dev.chunkfun.world;

/**
 * Inclusive rectangle of chunk coordinates in which vanilla terrain is generated.
 * Everything outside the mask is generated as empty void.
 */
public record GenerationMask(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {

    /** A mask that contains no chunk at all (the whole world stays void). */
    public static final GenerationMask NONE = new GenerationMask(1, 1, 0, 0);

    public static GenerationMask single(int chunkX, int chunkZ) {
        return new GenerationMask(chunkX, chunkZ, chunkX, chunkZ);
    }

    public static GenerationMask square(int centerChunkX, int centerChunkZ, int radius) {
        return new GenerationMask(centerChunkX - radius, centerChunkZ - radius, centerChunkX + radius, centerChunkZ + radius);
    }

    public boolean contains(int chunkX, int chunkZ) {
        return chunkX >= this.minChunkX && chunkX <= this.maxChunkX && chunkZ >= this.minChunkZ && chunkZ <= this.maxChunkZ;
    }

    /** This mask grown by {@code radius} chunks on every side. */
    public GenerationMask expand(int radius) {
        return new GenerationMask(this.minChunkX - radius, this.minChunkZ - radius,
                this.maxChunkX + radius, this.maxChunkZ + radius);
    }

    public boolean isEmpty() {
        return this.minChunkX > this.maxChunkX || this.minChunkZ > this.maxChunkZ;
    }

    public int chunkCount() {
        return this.isEmpty() ? 0 : (this.maxChunkX - this.minChunkX + 1) * (this.maxChunkZ - this.minChunkZ + 1);
    }
}
