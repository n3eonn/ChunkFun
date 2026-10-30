package dev.chunkfun.world;

import java.util.Random;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * A generator that lets the vanilla generator do all the work, but only inside a configurable
 * {@link GenerationMask}. Every chunk outside the mask stays empty void.
 *
 * <p>Paper's {@code CustomChunkGenerator} asks the per-chunk overloads below before running each
 * vanilla stage (noise, surface, carvers, decoration, initial mobs and structure starts). That means the
 * playable chunk gets real vanilla terrain (ores, caves, aquifers, trees, lava lakes, structure pieces)
 * while its neighbours are plain void.</p>
 *
 * <p>Structure <em>starts</em> are allowed everywhere while an environment is being prepared. A start is only
 * bookkeeping: blocks of a structure piece are placed while the chunk containing them is decorated, and
 * decoration only runs inside the mask. This means structures centred just outside the playable chunk still
 * extend into it, and means {@code locateNearestStructure} and structure bounding boxes work before any
 * terrain exists. Once the environment is prepared, starts are limited to the area around the mask
 * (see {@link #structureMask(GenerationMask)}).</p>
 *
 * <p>The mask is read from world-generation worker threads, so it is held in a volatile field and only
 * replaced as a whole.</p>
 */
public final class MaskedVanillaGenerator extends ChunkGenerator {

    private volatile GenerationMask mask = GenerationMask.NONE;
    /** Where structure starts are computed; {@code null} means everywhere (needed while locating structures). */
    private volatile GenerationMask structureMask;

    public GenerationMask mask() {
        return this.mask;
    }

    public void mask(GenerationMask mask) {
        this.mask = mask;
    }

    /**
     * Restricts structure-start computation once the environment is prepared. Players flying through the void
     * load many empty chunks; without this, each of them would still evaluate every structure placement
     * (villages, ancient cities, ...) on the world-generation threads, which delays chunk loading while moving.
     */
    public void structureMask(GenerationMask structureMask) {
        this.structureMask = structureMask;
    }

    private boolean inside(int chunkX, int chunkZ) {
        return this.mask.contains(chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return this.inside(chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return this.inside(chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return this.inside(chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateDecorations(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return this.inside(chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateMobs(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return this.inside(chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateStructures(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        GenerationMask structures = this.structureMask;
        return structures == null || structures.contains(chunkX, chunkZ);
    }

    // The no-argument variants are only consulted by legacy code paths; keep them consistent with vanilla.
    @Override
    public boolean shouldGenerateNoise() {
        return true;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return true;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return true;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return true;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return true;
    }
}
