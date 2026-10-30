package dev.chunkfun.world;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.config.ChunkFunConfig;
import io.papermc.paper.math.Position;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.StructureSearchResult;
import org.bukkit.util.BiomeSearchResult;
import org.jetbrains.annotations.Nullable;

/**
 * Creates and prepares environments.
 *
 * <p>Pipeline (every step runs on the main thread; the expensive part, chunk generation, runs on Paper's
 * world-generation workers through {@code getChunkAtAsync}):</p>
 * <ol>
 *     <li>Create a fresh keyed world using {@link MaskedVanillaGenerator} with an empty mask, so nothing
 *     is generated yet. A forced spawn position prevents vanilla's spawn search from loading chunks.</li>
 *     <li>Pick the playable chunk(s) without generating terrain, using biome search, structure locating
 *     and structure piece bounding boxes (structure starts are bookkeeping only).</li>
 *     <li>Set the generation mask and generate the playable chunks asynchronously.</li>
 *     <li>Post-process: guarantee required features, find the safe spawn, set the world border and keep
 *     the playable chunks loaded with plugin tickets.</li>
 * </ol>
 */
public final class EnvironmentBuilder {

    /** Highest Y scans may use in the Nether: the bedrock roof starts at y=123. */
    private static final int NETHER_SCAN_CEILING = 121;

    private final ChunkFunPlugin plugin;
    private final Random random = new Random();
    private int counter;
    private @Nullable BlockVector shipTemplateSize;
    private boolean shipTemplateLoaded;

    private record Plan(GenerationMask generate, GenerationMask load, String description,
                        @Nullable BoundingBox ship) {
    }

    public EnvironmentBuilder(ChunkFunPlugin plugin) {
        this.plugin = plugin;
    }

    private ChunkFunConfig config() {
        return this.plugin.settings();
    }

    /**
     * Starts preparing an environment. Must be called on the main thread. The returned future completes on
     * the main thread. If preparation fails, the half-built world is retired before the future fails.
     */
    public CompletableFuture<Environment> build(EnvironmentVariant variant) {
        Environment environment;
        try {
            environment = this.createWorld(variant);
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }

        CompletableFuture<Environment> result = new CompletableFuture<>();
        CompletableFuture<Plan> plan;
        try {
            plan = CompletableFuture.completedFuture(switch (variant) {
                case OVERWORLD -> this.planOverworld(environment);
                case NETHER -> this.planNether(environment);
                case END_CITY -> this.planEndCity(environment);
                case END_ISLAND -> this.planEndIsland();
                case ANCIENT_CITY -> this.planAncientCity(environment);
            });
        } catch (Throwable throwable) {
            plan = CompletableFuture.failedFuture(throwable);
        }

        plan.thenCompose(p -> this.generate(environment, p).thenApply(ignored -> p))
                .thenCompose(p -> this.postProcess(environment, p))
                .whenComplete((ignored, error) -> {
                    if (error == null && environment.isUsable()) {
                        result.complete(environment);
                        return;
                    }
                    Throwable cause = unwrap(error == null ? new IllegalStateException("environment has no spawn") : error);
                    this.discard(environment);
                    result.completeExceptionally(cause);
                });
        return result;
    }

    /** Throws away an environment that never became active. */
    public void discard(Environment environment) {
        if (Bukkit.getWorld(environment.world().getUID()) != null) {
            this.plugin.janitor().retire(environment, this.plugin::lobbySpawn, null);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    // --- step 1: world creation ------------------------------------------------------------------------------

    private Environment createWorld(EnvironmentVariant variant) {
        String id = variant.id() + "_" + Long.toString(System.currentTimeMillis(), 36) + "_" + (this.counter++);
        NamespacedKey key = new NamespacedKey(this.plugin, id);
        MaskedVanillaGenerator generator = new MaskedVanillaGenerator();
        long seed = this.config().seed().orElseGet(this.random::nextLong);

        WorldCreator creator = WorldCreator.ofKey(key)
                .environment(variant.bukkitEnvironment())
                .generator(generator)
                .seed(seed)
                .generateStructures(true)
                .forcedSpawnPosition(Position.block(0, 100, 0), 0.0f, 0.0f);
        World world = creator.createWorld();
        if (world == null) {
            throw new IllegalStateException("The server refused to create world " + key);
        }
        this.plugin.janitor().register(world.getWorldPath());
        world.setAutoSave(false);
        world.setDifficulty(this.plugin.lobbyWorld().getDifficulty());
        this.plugin.getLogger().info("Created " + variant + " world " + world.getName() + " (seed " + seed + ")");
        return new Environment(variant, world, generator, world.getWorldPath(), seed);
    }

    // --- step 2: choosing the playable chunk -----------------------------------------------------------------

    private Plan planOverworld(Environment environment) {
        World world = environment.world();
        ChunkFunConfig.OverworldSettings settings = this.config().overworld();
        Biome[] biomes = settings.preferredBiomes().toArray(Biome[]::new);
        Set<Biome> preferred = new HashSet<>(settings.preferredBiomes());
        int chunkX = 0;
        int chunkZ = 0;
        String description = "Overworld";
        BiomeSearchResult fallback = null;
        BiomeSearchResult result = null;
        // Biome search samples the biome source; it does not load or generate chunks.
        for (int attempt = 0; attempt < 12 && result == null; attempt++) {
            Location origin = attempt == 0
                    ? new Location(world, 0, 64, 0)
                    : new Location(world, this.random.nextInt(4001) - 2000, 64, this.random.nextInt(4001) - 2000);
            BiomeSearchResult found = world.locateNearestBiome(origin, settings.biomeSearchRadius(), 32, 64, biomes);
            if (found == null) {
                continue;
            }
            if (fallback == null) {
                fallback = found;
            }
            // Only accept a chunk that lies completely in preferred land biomes, so it is not cut by a river,
            // lake shore or ocean (players should spawn on dry ground at the surface).
            if (isEntirelyIn(world, found.getLocation().getBlockX() >> 4, found.getLocation().getBlockZ() >> 4, preferred)) {
                result = found;
            }
        }
        if (result == null) {
            result = fallback;
        }
        if (result != null) {
            chunkX = result.getLocation().getBlockX() >> 4;
            chunkZ = result.getLocation().getBlockZ() >> 4;
            NamespacedKey biomeKey = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).getKey(result.getBiome());
            description = biomeKey == null ? "Overworld" : prettify(biomeKey.getKey());
        } else {
            this.plugin.getLogger().warning("No preferred overworld biome within " + settings.biomeSearchRadius()
                    + " blocks; using chunk 0,0 (a tree will be planted if needed).");
        }
        GenerationMask mask = GenerationMask.single(chunkX, chunkZ);
        return new Plan(mask, mask, description, null);
    }

    /** Samples a 3x3 grid of the chunk at surface height through the biome source (no chunk loading). */
    private static boolean isEntirelyIn(World world, int chunkX, int chunkZ, Set<Biome> biomes) {
        for (int dx : new int[]{0, 8, 15}) {
            for (int dz : new int[]{0, 8, 15}) {
                Biome biome = world.vanillaBiomeProvider().getBiome(world, (chunkX << 4) + dx, 64, (chunkZ << 4) + dz);
                if (!biomes.contains(biome)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Plan planNether(Environment environment) {
        World world = environment.world();
        ChunkFunConfig.NetherSettings settings = this.config().nether();
        List<Structure> order = new ArrayList<>(2);
        switch (settings.structure()) {
            case BASTION -> order.add(Structure.BASTION_REMNANT);
            case FORTRESS -> order.add(Structure.FORTRESS);
            case RANDOM -> {
                if (this.random.nextBoolean()) {
                    order.add(Structure.BASTION_REMNANT);
                } else {
                    order.add(Structure.FORTRESS);
                }
            }
        }
        order.add(order.getFirst() == Structure.FORTRESS ? Structure.BASTION_REMNANT : Structure.FORTRESS);

        Location origin = new Location(world, 0, 64, 0);
        for (Structure structure : order) {
            StructureSearchResult result = world.locateNearestStructure(origin, structure, settings.searchRadiusChunks(), false);
            if (result == null) {
                continue;
            }
            GeneratedStructure generated = this.structureAt(world, result);
            String name = structure == Structure.FORTRESS ? "Nether Fortress" : "Bastion Remnant";
            if (generated == null || generated.getPieces().isEmpty()) {
                int chunkX = result.getLocation().getBlockX() >> 4;
                int chunkZ = result.getLocation().getBlockZ() >> 4;
                GenerationMask mask = GenerationMask.single(chunkX, chunkZ);
                return new Plan(mask, mask, name, null);
            }
            long best = densestChunk(generated.getPieces(), null);
            GenerationMask mask = GenerationMask.single(unpackX(best), unpackZ(best));
            return new Plan(mask, mask, name, null);
        }
        this.plugin.getLogger().warning("No bastion or fortress found near the Nether origin; using plain Nether terrain.");
        GenerationMask mask = GenerationMask.single(0, 0);
        return new Plan(mask, mask, "Nether Wastes", null);
    }

    private Plan planEndCity(Environment environment) {
        World world = environment.world();
        ChunkFunConfig.EndSettings settings = this.config().end();
        Set<Long> seen = new HashSet<>();
        GenerationMask fallback = null;

        for (int attempt = 0; attempt < settings.citySearchAttempts(); attempt++) {
            double angle = this.random.nextDouble() * Math.PI * 2.0;
            double distance = settings.citySearchMinDistance()
                    + this.random.nextDouble() * (settings.citySearchMaxDistance() - settings.citySearchMinDistance());
            Location origin = new Location(world, Math.cos(angle) * distance, 64, Math.sin(angle) * distance);
            StructureSearchResult result = world.locateNearestStructure(origin, Structure.END_CITY, 24, false);
            if (result == null) {
                continue;
            }
            int startX = result.getLocation().getBlockX() >> 4;
            int startZ = result.getLocation().getBlockZ() >> 4;
            if (!seen.add(pack(startX, startZ))) {
                continue;
            }
            GeneratedStructure city = this.structureAt(world, result);
            if (city == null) {
                continue;
            }
            if (fallback == null) {
                long best = densestChunk(city.getPieces(), null);
                fallback = GenerationMask.single(unpackX(best), unpackZ(best));
            }
            BoundingBox ship = this.findShip(city.getPieces());
            if (ship != null) {
                GenerationMask shipChunks = new GenerationMask(
                        (int) Math.floor(ship.getMinX()) >> 4, (int) Math.floor(ship.getMinZ()) >> 4,
                        (int) Math.floor(ship.getMaxX()) >> 4, (int) Math.floor(ship.getMaxZ()) >> 4);
                return new Plan(shipChunks, shipChunks, "End City + End Ship", ship);
            }
        }
        if (fallback != null) {
            if (settings.requireShip()) {
                this.plugin.getLogger().info("No End City with a ship found in " + settings.citySearchAttempts()
                        + " attempts; using an End City without a ship.");
            }
            return new Plan(fallback, fallback, "End City", null);
        }
        BiomeSearchResult highlands = world.locateNearestBiome(new Location(world, settings.citySearchMinDistance(), 64, 0),
                3000, 64, 64, Biome.END_HIGHLANDS);
        if (highlands != null) {
            GenerationMask mask = GenerationMask.single(highlands.getLocation().getBlockX() >> 4,
                    highlands.getLocation().getBlockZ() >> 4);
            return new Plan(mask, mask, "End Highlands", null);
        }
        throw new IllegalStateException("No End City or outer End island could be located");
    }

    /**
     * Ancient City: locates a city in the deep dark and uses its densest chunk (usually the centre with the
     * portal frame). The whole column is generated, including the overworld above the city.
     */
    private Plan planAncientCity(Environment environment) {
        World world = environment.world();
        ChunkFunConfig.AncientCitySettings settings = this.config().ancientCity();
        StructureSearchResult result = world.locateNearestStructure(new Location(world, 0, -27, 0),
                Structure.ANCIENT_CITY, settings.searchRadiusChunks(), false);
        if (result == null) {
            throw new IllegalStateException("No Ancient City within " + settings.searchRadiusChunks() + " chunks");
        }
        GeneratedStructure city = this.structureAt(world, result);
        GenerationMask mask;
        if (city == null || city.getPieces().isEmpty()) {
            mask = GenerationMask.single(result.getLocation().getBlockX() >> 4, result.getLocation().getBlockZ() >> 4);
        } else {
            long best = densestChunk(city.getPieces(), null);
            mask = GenerationMask.single(unpackX(best), unpackZ(best));
        }
        return new Plan(mask, mask, "Ancient City", null);
    }

    private Plan planEndIsland() {
        ChunkFunConfig.EndSettings settings = this.config().end();
        return new Plan(GenerationMask.square(0, 0, settings.islandRadiusChunks()),
                GenerationMask.square(0, 0, settings.islandPregenerateRadiusChunks()),
                "Main End Island", null);
    }

    /**
     * Returns the structure a locate result refers to, with all of its pieces. This loads the start chunk to
     * the STRUCTURE_REFERENCES status only, which never runs terrain generation, so it does not interfere
     * with the generation mask.
     */
    private @Nullable GeneratedStructure structureAt(World world, StructureSearchResult result) {
        int chunkX = result.getLocation().getBlockX() >> 4;
        int chunkZ = result.getLocation().getBlockZ() >> 4;
        Collection<GeneratedStructure> structures = world.getStructures(chunkX, chunkZ, result.getStructure());
        GeneratedStructure best = null;
        for (GeneratedStructure structure : structures) {
            if (structure.getBoundingBox().contains(result.getLocation().toVector())) {
                return structure;
            }
            if (best == null) {
                best = structure;
            }
        }
        return best;
    }

    /**
     * Identifies the End Ship piece of an End City. The ship is the only piece matching the size of the
     * vanilla {@code end_city/ship} template (in either horizontal orientation); if the template cannot be
     * read, the ship is recognised as the only piece longer than 20 blocks.
     */
    private @Nullable BoundingBox findShip(Collection<StructurePiece> pieces) {
        BlockVector template = this.shipTemplateSize();
        BoundingBox longest = null;
        int longestLength = 0;
        for (StructurePiece piece : pieces) {
            BoundingBox box = piece.getBoundingBox();
            // Bukkit keeps vanilla's inclusive maximum corner, so add one to get block counts.
            int sizeX = (int) Math.round(box.getMaxX() - box.getMinX()) + 1;
            int sizeY = (int) Math.round(box.getMaxY() - box.getMinY()) + 1;
            int sizeZ = (int) Math.round(box.getMaxZ() - box.getMinZ()) + 1;
            if (template != null) {
                int tx = template.getBlockX();
                int ty = template.getBlockY();
                int tz = template.getBlockZ();
                boolean sameHeight = Math.abs(sizeY - ty) <= 1;
                boolean straight = Math.abs(sizeX - tx) <= 1 && Math.abs(sizeZ - tz) <= 1;
                boolean rotated = Math.abs(sizeX - tz) <= 1 && Math.abs(sizeZ - tx) <= 1;
                if (sameHeight && (straight || rotated)) {
                    return box;
                }
            }
            int length = Math.max(sizeX, sizeZ);
            if (length > longestLength) {
                longestLength = length;
                longest = box;
            }
        }
        return longestLength > 20 ? longest : null;
    }

    private @Nullable BlockVector shipTemplateSize() {
        if (!this.shipTemplateLoaded) {
            this.shipTemplateLoaded = true;
            try {
                org.bukkit.structure.Structure template = Bukkit.getStructureManager()
                        .loadStructure(NamespacedKey.minecraft("end_city/ship"), false);
                if (template != null) {
                    this.shipTemplateSize = template.getSize();
                }
            } catch (RuntimeException ex) {
                this.plugin.getLogger().fine("Could not read the end_city/ship template: " + ex.getMessage());
            }
        }
        return this.shipTemplateSize;
    }

    /** The chunk with the largest structure-piece volume, optionally ignoring one chunk. */
    private static long densestChunk(Collection<StructurePiece> pieces, @Nullable Long excluded) {
        Map<Long, Double> volume = new HashMap<>();
        for (StructurePiece piece : pieces) {
            BoundingBox box = piece.getBoundingBox();
            int minX = (int) Math.floor(box.getMinX());
            int maxX = (int) Math.floor(box.getMaxX());
            int minZ = (int) Math.floor(box.getMinZ());
            int maxZ = (int) Math.floor(box.getMaxZ());
            double height = box.getMaxY() - box.getMinY() + 1;
            for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    int overlapX = Math.min(maxX, (cx << 4) + 15) - Math.max(minX, cx << 4) + 1;
                    int overlapZ = Math.min(maxZ, (cz << 4) + 15) - Math.max(minZ, cz << 4) + 1;
                    if (overlapX > 0 && overlapZ > 0) {
                        volume.merge(pack(cx, cz), overlapX * overlapZ * height, Double::sum);
                    }
                }
            }
        }
        long best = 0L;
        double bestVolume = -1.0;
        for (Map.Entry<Long, Double> entry : volume.entrySet()) {
            if (excluded != null && entry.getKey().longValue() == excluded) {
                continue;
            }
            if (entry.getValue() > bestVolume) {
                bestVolume = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    // --- step 3: generation ----------------------------------------------------------------------------------

    private CompletableFuture<Void> generate(Environment environment, Plan plan) {
        environment.generator().mask(plan.generate());
        World world = environment.world();
        GenerationMask load = plan.load();
        CompletableFuture<Void> done = new CompletableFuture<>();
        // Paper generates these on its worker threads and invokes the callback on the main thread.
        world.getChunksAtAsync(load.minChunkX(), load.minChunkZ(), load.maxChunkX(), load.maxChunkZ(), true, () -> {
            if (load.chunkCount() <= 16) {
                // Hold small regions loaded until post-processing (which may span several ticks) is finished.
                for (int cx = load.minChunkX(); cx <= load.maxChunkX(); cx++) {
                    for (int cz = load.minChunkZ(); cz <= load.maxChunkZ(); cz++) {
                        world.addPluginChunkTicket(cx, cz, this.plugin);
                    }
                }
            }
            done.complete(null);
        });
        return done;
    }

    // --- step 4: post-processing -----------------------------------------------------------------------------

    private CompletableFuture<Environment> postProcess(Environment environment, Plan plan) {
        World world = environment.world();
        environment.description(plan.description());
        CompletableFuture<GenerationMask> playable = switch (environment.variant()) {
            case OVERWORLD -> {
                this.ensureOverworldFeatures(environment, plan.generate());
                yield CompletableFuture.completedFuture(plan.generate());
            }
            case NETHER -> {
                this.ensureNetherFeatures(environment, plan.generate());
                yield CompletableFuture.completedFuture(plan.generate());
            }
            case END_CITY -> plan.ship() == null
                    ? CompletableFuture.completedFuture(plan.generate())
                    : this.isolateElytraChunk(environment, plan);
            case END_ISLAND, ANCIENT_CITY -> CompletableFuture.completedFuture(plan.generate());
        };

        return playable.thenApply(mask -> {
            int maxY = switch (environment.variant()) {
                case NETHER -> NETHER_SCAN_CEILING;
                // Scans start below the overworld above the city, so spawns, mobs and supplies land inside it.
                case ANCIENT_CITY -> this.config().ancientCity().scanCeilingY();
                default -> world.getMaxHeight() - 1;
            };
            PlayableArea area = PlayableArea.ofChunks(world, mask, world.getMinHeight(), maxY);
            environment.area(area);

            Location spawn;
            if (environment.variant() == EnvironmentVariant.END_ISLAND) {
                // Keep players off the exit-portal fountain at the origin.
                spawn = SurfaceScanner.findPlayerSpawn(area, 16.5, 16.5, 0.0);
            } else if (environment.variant() == EnvironmentVariant.NETHER) {
                // On a cavern floor above the lava sea, never in a pocket right under the bedrock roof.
                spawn = SurfaceScanner.findCavernSpawn(area, 33, 5);
                if (spawn == null) {
                    spawn = SurfaceScanner.findPlayerSpawn(area, area.centerX(), area.centerZ(), 0.0);
                }
            } else {
                spawn = SurfaceScanner.findPlayerSpawn(area, area.centerX(), area.centerZ(), 0.0);
            }
            if (spawn == null) {
                this.plugin.getLogger().warning("No natural safe spawn in " + world.getName() + "; building a platform.");
                spawn = SurfaceScanner.buildEmergencyPlatform(area, environment.variant().fallbackFloor());
            }
            spawn.setYaw(this.random.nextFloat() * 360.0f - 180.0f);
            environment.spawn(spawn);
            world.setSpawnLocation(spawn);

            if (this.config().worldBorder()) {
                WorldBorder border = world.getWorldBorder();
                border.setCenter(area.centerX(), area.centerZ());
                border.setSize(Math.max(area.widthX(), area.widthZ()));
                border.setDamageBuffer(0.0);
                border.setDamageAmount(2.0);
                border.setWarningDistance(1);
            }
            // Structure references of the playable chunks are complete now; void chunks loaded later (players
            // flying around) no longer need to evaluate structure placements. Vanilla references reach 8 chunks.
            environment.generator().structureMask(mask.expand(8));

            // Keep the playable chunks (or, for the large island, the spawn chunk) loaded while the environment exists.
            world.removePluginChunkTickets(this.plugin);
            if (mask.chunkCount() <= 9) {
                for (int cx = mask.minChunkX(); cx <= mask.maxChunkX(); cx++) {
                    for (int cz = mask.minChunkZ(); cz <= mask.maxChunkZ(); cz++) {
                        world.addPluginChunkTicket(cx, cz, this.plugin);
                    }
                }
            } else {
                world.addPluginChunkTicket(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4, this.plugin);
            }
            environment.phase(Environment.Phase.READY);
            this.plugin.getLogger().info("Prepared " + environment.variant() + " (" + plan.description() + ") at "
                    + area + ", spawn " + spawn.getBlockX() + " " + spawn.getBlockY() + " " + spawn.getBlockZ());
            this.plugin.getLogger().info("Contents of " + world.getName() + ": " + this.summarize(environment));
            return environment;
        });
    }

    /** Guarantees at least one natural tree in the overworld chunk. */
    private void ensureOverworldFeatures(Environment environment, GenerationMask mask) {
        World world = environment.world();
        Chunk chunk = world.getChunkAt(mask.minChunkX(), mask.minChunkZ());
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, false, false);
        int logs = 0;
        int ores = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = Math.min(world.getMaxHeight() - 1, snapshot.getHighestBlockYAt(x, z) + 1);
                for (int y = world.getMinHeight(); y <= top; y++) {
                    Material type = snapshot.getBlockType(x, y, z);
                    if (Tag.LOGS.isTagged(type)) {
                        logs++;
                    } else if (type.name().endsWith("_ORE")) {
                        ores++;
                    }
                }
            }
        }
        this.plugin.getLogger().fine("Overworld chunk: " + logs + " logs, " + ores + " ore blocks");
        if (logs > 0 || !this.config().overworld().guaranteeTree()) {
            return;
        }
        int baseX = mask.minChunkX() << 4;
        int baseZ = mask.minChunkZ() << 4;
        PlayableArea area = PlayableArea.ofChunks(world, mask, world.getMinHeight(), world.getMaxHeight() - 1);
        List<int[]> columns = new ArrayList<>();
        for (int x = 3; x <= 12; x++) {
            for (int z = 3; z <= 12; z++) {
                columns.add(new int[]{baseX + x, baseZ + z});
            }
        }
        columns.sort((a, b) -> Double.compare(distanceSq(a, area), distanceSq(b, area)));
        for (int[] column : columns) {
            int y = SurfaceScanner.standY(area, column[0], column[1], 8, false);
            if (y == SurfaceScanner.NONE) {
                continue;
            }
            Material floor = world.getType(column[0], y - 1, column[1]);
            if (!Tag.DIRT.isTagged(floor)) {
                continue;
            }
            TreeType type = this.random.nextInt(3) == 0 ? TreeType.BIRCH : TreeType.TREE;
            if (world.generateTree(new Location(world, column[0], y, column[1]), this.random, type)) {
                this.plugin.getLogger().info("Planted a tree because the overworld chunk generated without one.");
                return;
            }
        }
        // No dirt surface at all (e.g. a stony peak): give one column a grass floor and grow the tree there.
        int x = baseX + 8;
        int z = baseZ + 8;
        int y = SurfaceScanner.standY(area, x, z, 1, false);
        if (y != SurfaceScanner.NONE && y < world.getMaxHeight() - 10) {
            world.getBlockAt(x, y - 1, z).setType(Material.GRASS_BLOCK, false);
            for (int dy = 0; dy < 8; dy++) {
                if (!world.getType(x, y + dy, z).isAir()) {
                    world.getBlockAt(x, y + dy, z).setType(Material.AIR, false);
                }
            }
            world.generateTree(new Location(world, x, y, z), this.random, TreeType.TREE);
        }
    }

    private static double distanceSq(int[] column, PlayableArea area) {
        double dx = column[0] + 0.5 - area.centerX();
        double dz = column[1] + 0.5 - area.centerZ();
        return dx * dx + dz * dz;
    }

    /** Guarantees ancient debris, quartz and lava in the Nether chunk by converting natural netherrack. */
    private void ensureNetherFeatures(Environment environment, GenerationMask mask) {
        World world = environment.world();
        ChunkFunConfig.NetherSettings settings = this.config().nether();
        Chunk chunk = world.getChunkAt(mask.minChunkX(), mask.minChunkZ());
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        int debris = 0;
        int quartz = 0;
        int lava = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = world.getMinHeight(); y < 128; y++) {
                    Material type = snapshot.getBlockType(x, y, z);
                    if (type == Material.ANCIENT_DEBRIS) {
                        debris++;
                    } else if (type == Material.NETHER_QUARTZ_ORE) {
                        quartz++;
                    } else if (type == Material.LAVA) {
                        lava++;
                    }
                }
            }
        }
        int baseX = mask.minChunkX() << 4;
        int baseZ = mask.minChunkZ() << 4;
        if (debris == 0 && settings.guaranteeDebris()) {
            // Vanilla places debris between y=8 and y=119 (mostly low); try the low band first.
            int placed = this.replaceEmbedded(world, baseX, baseZ, 8, 22, Material.ANCIENT_DEBRIS, 2);
            if (placed == 0) {
                placed = this.replaceEmbedded(world, baseX, baseZ, 8, 119, Material.ANCIENT_DEBRIS, 2);
            }
            this.plugin.getLogger().info("Nether chunk had no ancient debris; embedded " + placed + " block(s).");
        }
        if (quartz == 0 && settings.guaranteeQuartz()) {
            this.replaceEmbedded(world, baseX, baseZ, 10, 117, Material.NETHER_QUARTZ_ORE, 4);
        }
        if (lava == 0 && settings.guaranteeLava()) {
            this.addLavaSpring(world, baseX, baseZ);
        }
    }

    /** Replaces netherrack that is fully enclosed (never visible) with an ore, as a small cluster. */
    private int replaceEmbedded(World world, int baseX, int baseZ, int minY, int maxY, Material ore, int clusterSize) {
        for (int attempt = 0; attempt < 400; attempt++) {
            int x = baseX + 1 + this.random.nextInt(14);
            int z = baseZ + 1 + this.random.nextInt(14);
            int y = minY + this.random.nextInt(Math.max(1, maxY - minY + 1));
            if (!this.isEnclosedNetherrack(world, x, y, z)) {
                continue;
            }
            world.getBlockAt(x, y, z).setType(ore, false);
            int placed = 1;
            for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH}) {
                if (placed >= clusterSize) {
                    break;
                }
                int nx = x + face.getModX();
                int ny = y + face.getModY();
                int nz = z + face.getModZ();
                if ((nx >> 4) == (baseX >> 4) && (nz >> 4) == (baseZ >> 4) && this.isEnclosedNetherrack(world, nx, ny, nz)) {
                    world.getBlockAt(nx, ny, nz).setType(ore, false);
                    placed++;
                }
            }
            return placed;
        }
        return 0;
    }

    private boolean isEnclosedNetherrack(World world, int x, int y, int z) {
        if (world.getType(x, y, z) != Material.NETHERRACK) {
            return false;
        }
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH}) {
            Material neighbour = world.getType(x + face.getModX(), y + face.getModY(), z + face.getModZ());
            if (!neighbour.isSolid() || neighbour == Material.ANCIENT_DEBRIS) {
                return false;
            }
        }
        return true;
    }

    /** Turns a netherrack wall next to open air into a lava source, which is what a vanilla lava spring is. */
    private void addLavaSpring(World world, int baseX, int baseZ) {
        BlockFace[] sides = {BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH};
        for (int attempt = 0; attempt < 600; attempt++) {
            int x = baseX + 1 + this.random.nextInt(14);
            int z = baseZ + 1 + this.random.nextInt(14);
            int y = 40 + this.random.nextInt(60);
            if (world.getType(x, y, z) != Material.NETHERRACK || !world.getType(x, y + 1, z).isSolid()) {
                continue;
            }
            for (BlockFace side : sides) {
                if (world.getType(x + side.getModX(), y, z + side.getModZ()).isAir()) {
                    world.getBlockAt(x, y, z).setType(Material.LAVA, true);
                    this.plugin.getLogger().info("Nether chunk had no lava; added a lava spring.");
                    return;
                }
            }
        }
    }

    /**
     * End City variant: the chunks covered by the End Ship were generated. Finds the chunk with the ship's
     * elytra item frame, makes it the playable chunk and clears the other ship chunks back to void.
     */
    private CompletableFuture<GenerationMask> isolateElytraChunk(Environment environment, Plan plan) {
        World world = environment.world();
        GenerationMask generated = plan.generate();
        CompletableFuture<GenerationMask> result = new CompletableFuture<>();

        // World-gen entities are added when the chunk becomes FULL; wait a few ticks to be safe.
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            try {
                Long elytraChunk = null;
                for (int cx = generated.minChunkX(); cx <= generated.maxChunkX() && elytraChunk == null; cx++) {
                    for (int cz = generated.minChunkZ(); cz <= generated.maxChunkZ() && elytraChunk == null; cz++) {
                        for (Entity entity : world.getChunkAt(cx, cz).getEntities()) {
                            if (entity instanceof ItemFrame frame && frame.getItem().getType() == Material.ELYTRA) {
                                elytraChunk = pack(entity.getLocation().getBlockX() >> 4, entity.getLocation().getBlockZ() >> 4);
                                break;
                            }
                        }
                    }
                }
                long chosen;
                if (elytraChunk != null) {
                    chosen = elytraChunk;
                } else {
                    // The frame was not found (e.g. changed vanilla data); keep the chunk holding most of the ship.
                    BoundingBox ship = plan.ship();
                    chosen = pack((int) Math.floor(ship.getCenterX()) >> 4, (int) Math.floor(ship.getCenterZ()) >> 4);
                    environment.description("End City + End Ship (elytra not located)");
                    this.plugin.getLogger().warning("Could not find the End Ship elytra frame; using the ship's centre chunk.");
                }
                GenerationMask playable = GenerationMask.single(unpackX(chosen), unpackZ(chosen));
                environment.generator().mask(playable);

                List<long[]> toClear = new ArrayList<>();
                for (int cx = generated.minChunkX(); cx <= generated.maxChunkX(); cx++) {
                    for (int cz = generated.minChunkZ(); cz <= generated.maxChunkZ(); cz++) {
                        if (!playable.contains(cx, cz)) {
                            toClear.add(new long[]{cx, cz});
                        }
                    }
                }
                this.clearChunks(world, toClear).whenComplete((ignored, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(playable);
                    }
                });
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        }, 5L);
        return result;
    }

    /** Clears chunks back to void, spread over several ticks with a block budget. */
    private CompletableFuture<Void> clearChunks(World world, List<long[]> chunks) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (chunks.isEmpty()) {
            done.complete(null);
            return done;
        }
        List<int[]> positions = new ArrayList<>();
        for (long[] coords : chunks) {
            Chunk chunk = world.getChunkAt((int) coords[0], (int) coords[1]);
            for (Entity entity : chunk.getEntities()) {
                if (!(entity instanceof Player)) {
                    entity.remove();
                }
            }
            ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, false, false);
            int baseX = chunk.getX() << 4;
            int baseZ = chunk.getZ() << 4;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int top = Math.min(world.getMaxHeight() - 1, snapshot.getHighestBlockYAt(x, z) + 1);
                    for (int y = world.getMinHeight(); y <= top; y++) {
                        if (!snapshot.getBlockType(x, y, z).isAir()) {
                            positions.add(new int[]{baseX + x, y, baseZ + z});
                        }
                    }
                }
            }
        }
        int budget = this.config().worldEditBlocksPerTick();
        Bukkit.getScheduler().runTaskTimer(this.plugin, task -> {
            if (Bukkit.getWorld(world.getUID()) == null) {
                task.cancel();
                done.completeExceptionally(new IllegalStateException("world unloaded while clearing"));
                return;
            }
            int processed = 0;
            while (!positions.isEmpty() && processed < budget) {
                int[] pos = positions.removeLast();
                world.getBlockAt(pos[0], pos[1], pos[2]).setType(Material.AIR, false);
                processed++;
            }
            if (positions.isEmpty()) {
                task.cancel();
                done.complete(null);
            }
        }, 1L, 1L);
        return done;
    }

    /** One-line description of what was generated, for the console. */
    private String summarize(Environment environment) {
        World world = environment.world();
        PlayableArea area = environment.area();
        if (!area.isSingleChunk()) {
            int crystals = world.getEntitiesByClass(EnderCrystal.class).size();
            return "dragon fight " + (world.getEnderDragonBattle() != null ? "present" : "MISSING")
                    + ", " + crystals + " end crystals in loaded chunks";
        }
        ChunkSnapshot snapshot = world.getChunkAt(area.minChunkX(), area.minChunkZ()).getChunkSnapshot(true, false, false);
        Map<String, Integer> counts = new TreeMap<>();
        int solid = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = Math.min(world.getMaxHeight() - 1, snapshot.getHighestBlockYAt(x, z) + 1);
                for (int y = world.getMinHeight(); y <= top; y++) {
                    Material type = snapshot.getBlockType(x, y, z);
                    if (type.isAir()) {
                        continue;
                    }
                    solid++;
                    String bucket = summaryBucket(type);
                    if (bucket != null) {
                        counts.merge(bucket, 1, Integer::sum);
                    }
                }
            }
        }
        int elytra = 0;
        for (Entity entity : world.getChunkAt(area.minChunkX(), area.minChunkZ()).getEntities()) {
            if (entity instanceof ItemFrame frame && frame.getItem().getType() == Material.ELYTRA) {
                elytra++;
            }
        }
        StringBuilder builder = new StringBuilder(solid + " blocks");
        counts.forEach((name, count) -> builder.append(", ").append(count).append(' ').append(name));
        if (environment.variant() == EnvironmentVariant.END_CITY) {
            builder.append(", ").append(elytra).append(" elytra item frame(s)");
        }
        return builder.toString();
    }

    private static @Nullable String summaryBucket(Material type) {
        if (Tag.LOGS.isTagged(type)) {
            return "logs";
        }
        if (type == Material.ANCIENT_DEBRIS) {
            return "ancient debris";
        }
        if (type == Material.NETHER_QUARTZ_ORE) {
            return "quartz ore";
        }
        if (type.name().endsWith("_ORE")) {
            return "other ores";
        }
        return switch (type) {
            case GRASS_BLOCK -> "grass";
            case DIRT -> "dirt";
            case STONE -> "stone";
            case DEEPSLATE -> "deepslate";
            case LAVA -> "lava";
            case NETHERRACK -> "netherrack";
            case BLACKSTONE, POLISHED_BLACKSTONE_BRICKS, GILDED_BLACKSTONE -> "bastion blocks";
            case NETHER_BRICKS, NETHER_BRICK_FENCE -> "fortress blocks";
            case END_STONE -> "end stone";
            case PURPUR_BLOCK, PURPUR_PILLAR -> "purpur";
            case CHEST -> "chests";
            case SCULK, SCULK_VEIN, SCULK_SENSOR, SCULK_CATALYST -> "sculk";
            case SCULK_SHRIEKER -> "sculk shriekers";
            case DEEPSLATE_BRICKS, DEEPSLATE_TILES, CRACKED_DEEPSLATE_BRICKS, CRACKED_DEEPSLATE_TILES,
                 POLISHED_DEEPSLATE, CHISELED_DEEPSLATE, REINFORCED_DEEPSLATE -> "ancient city blocks";
            default -> null;
        };
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private static long pack(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    private static int unpackX(long packed) {
        return (int) packed;
    }

    private static int unpackZ(long packed) {
        return (int) (packed >>> 32);
    }

    private static String prettify(String key) {
        String[] words = key.replace('_', ' ').split(" ");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.toString();
    }
}
