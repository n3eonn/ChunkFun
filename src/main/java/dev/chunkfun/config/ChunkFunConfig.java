package dev.chunkfun.config;

import dev.chunkfun.events.special.SpecialEventType;
import dev.chunkfun.supply.LuckyOutcome;
import dev.chunkfun.supply.SupplyTier;
import dev.chunkfun.util.WeightedTable;
import dev.chunkfun.world.EnvironmentType;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.logging.Logger;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

/**
 * Typed, validated view of config.yml. Every value is range-checked; invalid values fall back to the
 * documented default and produce a console warning naming the offending path.
 */
public final class ChunkFunConfig {

    public enum NetherStructure { RANDOM, BASTION, FORTRESS }

    public record Rotation(List<EnvironmentType> order, int durationSeconds, int countdownSeconds,
                           int prepareAheadSeconds, int preparationTimeoutSeconds) {
    }

    public record OverworldSettings(List<Biome> preferredBiomes, int biomeSearchRadius, boolean guaranteeTree) {
    }

    public record NetherSettings(NetherStructure structure, int searchRadiusChunks, boolean guaranteeDebris,
                                 boolean guaranteeQuartz, boolean guaranteeLava) {
    }

    public record EndSettings(double islandChance, int islandDurationSeconds, int islandRadiusChunks,
                              int islandPregenerateRadiusChunks, int citySearchAttempts, int citySearchMinDistance,
                              int citySearchMaxDistance, boolean requireShip) {
    }

    public record MobEventSettings(int intervalSeconds, WeightedTable<EntityType> mobs, WeightedTable<Integer> amounts) {
    }

    public record TntRainSettings(int spawnIntervalTicks, int fuseTicks, int dropHeight) {
    }

    public record SheepSettings(int count, double damage, double knockbackHorizontal, double knockbackVertical,
                                double speed, int ramCooldownTicks, double targetRange, double scale, double health) {
    }

    public record FloodSettings(int riseSeconds, int heightAboveSurface, int blocksPerTick, int maxBlocks,
                                int restoreBlocksPerTick) {
    }

    public record MeteorSettings(int spawnIntervalTicks, int maxActive, int fallTicks, int startHeight,
                                 float explosionPower, boolean setFire, boolean breakBlocks, double lavaChance) {
    }

    public record LightningSettings(int strikeIntervalTicks, int strikesPerInterval) {
    }

    public record AnvilRainSettings(int spawnIntervalTicks, int dropHeight, float damagePerBlock, int maxDamage) {
    }

    public record LowGravitySettings(int jumpBoostLevel) {
    }

    public record SpecialEventSettings(int intervalSeconds, int durationSeconds, Map<SpecialEventType, Double> weights,
                                       TntRainSettings tntRain, SheepSettings sheep, FloodSettings flood,
                                       MeteorSettings meteor, LightningSettings lightning, AnvilRainSettings anvilRain,
                                       LowGravitySettings lowGravity) {
    }

    public record SupplySettings(int minIntervalSeconds, int maxIntervalSeconds, int luckyBlockCount, boolean holograms,
                                 Material blockType, WeightedTable<SupplyTier> tiers, WeightedTable<LuckyOutcome> outcomes) {
    }

    public record EffectsSettings(boolean glowing, NamedTextColor glowColor, boolean ambientParticles, boolean fireworks) {
    }

    public record AncientCitySettings(int searchRadiusChunks, int scanCeilingY) {
    }

    public record ScoreboardSettings(boolean enabled, int updateIntervalTicks, Component title, boolean showWhenStopped) {
    }

    private final Logger logger;
    private final FileConfiguration file;
    private int warnings;

    private boolean autoStart;
    private boolean worldBorder;
    private AncientCitySettings ancientCity;
    private String lobbyWorld;
    private OptionalLong seed;
    private int largeAreaFocusRadius;
    private int worldEditBlocksPerTick;
    private Rotation rotation;
    private OverworldSettings overworld;
    private NetherSettings nether;
    private EndSettings end;
    private MobEventSettings mobEvents;
    private SpecialEventSettings specialEvents;
    private SupplySettings supply;
    private ScoreboardSettings scoreboard;
    private EffectsSettings effects;

    private ChunkFunConfig(FileConfiguration file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public static ChunkFunConfig load(FileConfiguration file, Logger logger) {
        ChunkFunConfig config = new ChunkFunConfig(file, logger);
        config.parse();
        if (config.warnings > 0) {
            logger.warning("config.yml contains " + config.warnings + " problem(s); defaults were used for those values.");
        }
        return config;
    }

    private void parse() {
        this.autoStart = this.file.getBoolean("game.auto-start", false);
        this.worldBorder = this.file.getBoolean("game.world-border", false);
        this.lobbyWorld = this.file.getString("game.lobby-world", "");
        this.largeAreaFocusRadius = this.intValue("game.large-area-focus-radius", 32, 8, 256);
        this.worldEditBlocksPerTick = this.intValue("game.world-edit-blocks-per-tick", 8000, 500, 100000);
        this.seed = this.parseSeed();

        List<EnvironmentType> order = new ArrayList<>();
        for (String raw : this.file.getStringList("rotation.order")) {
            EnvironmentType.parse(raw).ifPresentOrElse(order::add,
                    () -> this.warn("rotation.order", "unknown environment '" + raw + "' (use OVERWORLD, NETHER, END or ANCIENT_CITY)"));
        }
        if (order.isEmpty()) {
            if (this.file.isSet("rotation.order")) {
                this.warn("rotation.order", "no valid environments; using OVERWORLD, NETHER, END, ANCIENT_CITY");
            }
            order = List.of(EnvironmentType.OVERWORLD, EnvironmentType.NETHER, EnvironmentType.END, EnvironmentType.ANCIENT_CITY);
        }
        int countdown = this.intValue("rotation.countdown-seconds", 5, 1, 60);
        int duration = this.intValue("rotation.duration-seconds", 480, 30, 86400);
        if (duration <= countdown + 5) {
            this.warn("rotation.duration-seconds", "must be longer than the countdown; using 480");
            duration = 480;
        }
        int prepareAhead = this.intValue("rotation.prepare-ahead-seconds", 60, 10, 3600);
        this.rotation = new Rotation(List.copyOf(order), duration, countdown,
                Math.min(prepareAhead, duration - 1), this.intValue("rotation.preparation-timeout-seconds", 180, 30, 3600));

        this.overworld = new OverworldSettings(
                this.biomes("overworld.preferred-biomes"),
                this.intValue("overworld.biome-search-radius", 1600, 128, 10000),
                this.file.getBoolean("overworld.guarantee-tree", true));

        NetherStructure structure;
        String rawStructure = this.file.getString("nether.structure", "RANDOM");
        try {
            structure = NetherStructure.valueOf(rawStructure.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            this.warn("nether.structure", "'" + rawStructure + "' is not RANDOM, BASTION or FORTRESS; using RANDOM");
            structure = NetherStructure.RANDOM;
        }
        this.nether = new NetherSettings(structure,
                this.intValue("nether.structure-search-radius-chunks", 48, 8, 200),
                this.file.getBoolean("nether.guarantee-ancient-debris", true),
                this.file.getBoolean("nether.guarantee-quartz", true),
                this.file.getBoolean("nether.guarantee-lava", true));

        int minDistance = this.intValue("end.city-search-min-distance", 1500, 1100, 100000);
        int maxDistance = this.intValue("end.city-search-max-distance", 6000, 1200, 200000);
        if (maxDistance <= minDistance) {
            this.warn("end.city-search-max-distance", "must be greater than end.city-search-min-distance");
            maxDistance = minDistance + 4000;
        }
        int islandRadius = this.intValue("end.island-radius-chunks", 10, 7, 32);
        this.end = new EndSettings(
                this.doubleValue("end.island-chance", 0.25, 0.0, 1.0),
                this.intValue("end.island-duration-seconds", 720, 60, 86400),
                islandRadius,
                Math.min(islandRadius, this.intValue("end.island-pregenerate-radius-chunks", 8, 2, 32)),
                this.intValue("end.city-search-attempts", 10, 1, 50),
                minDistance, maxDistance,
                this.file.getBoolean("end.require-ship", true));

        this.ancientCity = new AncientCitySettings(
                this.intValue("ancient-city.search-radius-chunks", 100, 16, 400),
                this.intValue("ancient-city.scan-ceiling-y", -10, -60, 60));

        this.mobEvents = new MobEventSettings(
                this.intValue("mob-events.interval-seconds", 60, 10, 86400),
                this.mobTable(),
                this.amountTable());

        this.specialEvents = new SpecialEventSettings(
                this.intValue("special-events.interval-seconds", 120, 30, 86400),
                this.intValue("special-events.duration-seconds", 60, 10, 3600),
                this.eventWeights(),
                new TntRainSettings(
                        this.intValue("special-events.tnt-rain.spawn-interval-ticks", 20, 1, 1200),
                        this.intValue("special-events.tnt-rain.fuse-ticks", 80, 10, 400),
                        this.intValue("special-events.tnt-rain.drop-height", 30, 4, 200)),
                new SheepSettings(
                        this.intValue("special-events.sheep-adventure.count", 10, 1, 100),
                        this.doubleValue("special-events.sheep-adventure.damage", 7.0, 0.0, 100.0),
                        this.doubleValue("special-events.sheep-adventure.knockback-horizontal", 1.6, 0.0, 10.0),
                        this.doubleValue("special-events.sheep-adventure.knockback-vertical", 0.55, 0.0, 5.0),
                        this.doubleValue("special-events.sheep-adventure.speed", 1.5, 0.1, 5.0),
                        this.intValue("special-events.sheep-adventure.ram-cooldown-ticks", 40, 5, 1200),
                        this.doubleValue("special-events.sheep-adventure.target-range", 24.0, 2.0, 128.0),
                        this.doubleValue("special-events.sheep-adventure.scale", 1.3, 0.5, 4.0),
                        this.doubleValue("special-events.sheep-adventure.health", 16.0, 1.0, 1024.0)),
                new FloodSettings(
                        this.intValue("special-events.flood.rise-seconds", 20, 1, 3600),
                        this.intValue("special-events.flood.height-above-surface", 3, 0, 64),
                        this.intValue("special-events.flood.blocks-per-tick", 2500, 50, 100000),
                        this.intValue("special-events.flood.max-blocks", 250000, 1000, 5000000),
                        this.intValue("special-events.flood.restore-blocks-per-tick", 8000, 100, 200000)),
                new MeteorSettings(
                        this.intValue("special-events.meteor-attack.spawn-interval-ticks", 30, 2, 1200),
                        this.intValue("special-events.meteor-attack.max-active", 4, 1, 32),
                        this.intValue("special-events.meteor-attack.fall-ticks", 30, 5, 200),
                        this.intValue("special-events.meteor-attack.start-height", 45, 8, 300),
                        (float) this.doubleValue("special-events.meteor-attack.explosion-power", 3.0, 0.0, 10.0),
                        this.file.getBoolean("special-events.meteor-attack.set-fire", true),
                        this.file.getBoolean("special-events.meteor-attack.break-blocks", true),
                        this.doubleValue("special-events.meteor-attack.lava-chance", 0.5, 0.0, 1.0)),
                new LightningSettings(
                        this.intValue("special-events.lightning-storm.strike-interval-ticks", 15, 2, 1200),
                        this.intValue("special-events.lightning-storm.strikes-per-interval", 1, 1, 10)),
                new AnvilRainSettings(
                        this.intValue("special-events.anvil-rain.spawn-interval-ticks", 8, 1, 1200),
                        this.intValue("special-events.anvil-rain.drop-height", 25, 4, 200),
                        (float) this.doubleValue("special-events.anvil-rain.damage-per-block", 1.5, 0.0, 40.0),
                        this.intValue("special-events.anvil-rain.max-damage", 20, 0, 1000)),
                new LowGravitySettings(
                        this.intValue("special-events.low-gravity.jump-boost-level", 4, 1, 20)));
        if (this.specialEvents.durationSeconds() >= this.specialEvents.intervalSeconds()) {
            this.warn("special-events.duration-seconds", "is not shorter than the interval; events will run back to back");
        }

        int minSupply = this.intValue("supply.min-interval-seconds", 30, 10, 86400);
        int maxSupply = this.intValue("supply.max-interval-seconds", 150, 10, 86400);
        if (maxSupply < minSupply) {
            this.warn("supply.max-interval-seconds", "is smaller than supply.min-interval-seconds; swapping them");
            int swap = minSupply;
            minSupply = maxSupply;
            maxSupply = swap;
        }
        this.supply = new SupplySettings(minSupply, maxSupply,
                this.intValue("supply.lucky-block-count", 10, 1, 50),
                this.file.getBoolean("supply.holograms", true),
                this.luckyBlockType(),
                this.tierTable(),
                this.outcomeTable());
        this.effects = new EffectsSettings(
                this.file.getBoolean("effects.glowing", true),
                this.glowColor(),
                this.file.getBoolean("effects.ambient-particles", true),
                this.file.getBoolean("effects.fireworks", true));

        String rawTitle = this.file.getString("scoreboard.title", "<gold><bold>CHUNKFUN</bold></gold>");
        Component title;
        try {
            title = MiniMessage.miniMessage().deserialize(rawTitle);
        } catch (RuntimeException ex) {
            this.warn("scoreboard.title", "is not valid MiniMessage: " + ex.getMessage());
            title = MiniMessage.miniMessage().deserialize("<gold><bold>CHUNKFUN</bold></gold>");
        }
        this.scoreboard = new ScoreboardSettings(
                this.file.getBoolean("scoreboard.enabled", true),
                this.intValue("scoreboard.update-interval-ticks", 20, 1, 200),
                title,
                this.file.getBoolean("scoreboard.show-when-stopped", true));
    }

    // --- parsing helpers -------------------------------------------------------------------------------------

    private void warn(String path, String message) {
        this.warnings++;
        this.logger.warning("[config] " + path + ": " + message);
    }

    private int intValue(String path, int def, int min, int max) {
        if (!this.file.isSet(path)) {
            return def;
        }
        if (!this.file.isInt(path)) {
            this.warn(path, "'" + this.file.get(path) + "' is not a whole number; using " + def);
            return def;
        }
        int value = this.file.getInt(path);
        if (value < min || value > max) {
            this.warn(path, value + " is outside " + min + ".." + max + "; using " + def);
            return def;
        }
        return value;
    }

    private double doubleValue(String path, double def, double min, double max) {
        if (!this.file.isSet(path)) {
            return def;
        }
        if (!this.file.isDouble(path) && !this.file.isInt(path)) {
            this.warn(path, "'" + this.file.get(path) + "' is not a number; using " + def);
            return def;
        }
        double value = this.file.getDouble(path);
        if (Double.isNaN(value) || value < min || value > max) {
            this.warn(path, value + " is outside " + min + ".." + max + "; using " + def);
            return def;
        }
        return value;
    }

    private OptionalLong parseSeed() {
        Object raw = this.file.get("worlds.seed", "random");
        if (raw == null || "random".equalsIgnoreCase(String.valueOf(raw).trim())) {
            return OptionalLong.empty();
        }
        String text = String.valueOf(raw).trim();
        try {
            return OptionalLong.of(Long.parseLong(text));
        } catch (NumberFormatException ex) {
            // Non-numeric seeds are hashed like vanilla does.
            return OptionalLong.of(text.hashCode());
        }
    }

    private List<Biome> biomes(String path) {
        List<String> raw = this.file.getStringList(path);
        if (raw.isEmpty()) {
            raw = List.of("minecraft:forest", "minecraft:birch_forest", "minecraft:flower_forest",
                    "minecraft:old_growth_birch_forest", "minecraft:taiga", "minecraft:cherry_grove",
                    "minecraft:dark_forest", "minecraft:plains");
        }
        Registry<Biome> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
        List<Biome> biomes = new ArrayList<>();
        for (String entry : raw) {
            try {
                Biome biome = registry.get(Key.key(entry.trim().toLowerCase(Locale.ROOT)));
                if (biome == null) {
                    this.warn(path, "unknown biome '" + entry + "'");
                } else {
                    biomes.add(biome);
                }
            } catch (InvalidKeyException ex) {
                this.warn(path, "'" + entry + "' is not a valid biome key");
            }
        }
        if (biomes.isEmpty()) {
            this.warn(path, "no valid biomes; using minecraft:forest");
            biomes.add(Biome.FOREST);
        }
        return List.copyOf(biomes);
    }

    private WeightedTable<EntityType> mobTable() {
        String path = "mob-events.mobs";
        Map<EntityType, Double> defaults = new LinkedHashMap<>();
        defaults.put(EntityType.CREEPER, 25.0);
        defaults.put(EntityType.CHICKEN, 20.0);
        defaults.put(EntityType.WARDEN, 10.0);
        defaults.put(EntityType.WITHER, 15.0);
        defaults.put(EntityType.ZOMBIE, 10.0);
        defaults.put(EntityType.COW, 20.0);

        ConfigurationSection section = this.file.getConfigurationSection(path);
        if (section == null) {
            return WeightedTable.of(defaults);
        }
        Map<EntityType, Double> weights = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            EntityType type = parseEntityType(key);
            if (type == null || !type.isSpawnable() || !type.isAlive()) {
                this.warn(path + "." + key, "is not a spawnable living entity type");
                continue;
            }
            double weight = section.getDouble(key, -1.0);
            if (weight < 0.0) {
                this.warn(path + "." + key, "probability must be a number >= 0");
                continue;
            }
            weights.merge(type, weight, Double::sum);
        }
        return this.percentTable(path, weights, defaults);
    }

    private WeightedTable<Integer> amountTable() {
        String path = "mob-events.amounts";
        Map<Integer, Double> defaults = new LinkedHashMap<>();
        defaults.put(1, 10.0);
        defaults.put(2, 30.0);
        defaults.put(3, 20.0);
        defaults.put(5, 25.0);
        defaults.put(10, 15.0);

        ConfigurationSection section = this.file.getConfigurationSection(path);
        if (section == null) {
            return WeightedTable.of(defaults);
        }
        Map<Integer, Double> weights = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            int amount;
            try {
                amount = Integer.parseInt(key.trim());
            } catch (NumberFormatException ex) {
                this.warn(path + "." + key, "amount must be a whole number");
                continue;
            }
            if (amount < 1 || amount > 100) {
                this.warn(path + "." + key, "amount must be between 1 and 100");
                continue;
            }
            double weight = section.getDouble(key, -1.0);
            if (weight < 0.0) {
                this.warn(path + "." + key, "probability must be a number >= 0");
                continue;
            }
            weights.merge(amount, weight, Double::sum);
        }
        return this.percentTable(path, weights, defaults);
    }

    /** Validates that configured percentages total 100; otherwise warns and normalises them. */
    private <T> WeightedTable<T> percentTable(String path, Map<T, Double> weights, Map<T, Double> defaults) {
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (weights.isEmpty() || total <= 0.0) {
            this.warn(path, "no valid entries; using the default table");
            return WeightedTable.of(defaults);
        }
        if (Math.abs(total - 100.0) > 0.001) {
            this.warn(path, String.format(Locale.ROOT,
                    "probabilities add up to %.2f%% instead of 100%%; they will be scaled proportionally", total));
        }
        return WeightedTable.of(weights);
    }

    private static EntityType parseEntityType(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        for (EntityType type : EntityType.values()) {
            if (type != EntityType.UNKNOWN && type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        return null;
    }

    private Map<SpecialEventType, Double> eventWeights() {
        String path = "special-events.enabled";
        Map<SpecialEventType, Double> weights = new EnumMap<>(SpecialEventType.class);
        ConfigurationSection section = this.file.getConfigurationSection(path);
        for (SpecialEventType type : SpecialEventType.values()) {
            weights.put(type, 1.0);
        }
        if (section != null) {
            for (String key : section.getKeys(false)) {
                SpecialEventType.parse(key).ifPresentOrElse(
                        type -> weights.put(type, section.getBoolean(key, true) ? 1.0 : 0.0),
                        () -> this.warn(path + "." + key, "unknown special event"));
            }
        }
        if (weights.values().stream().noneMatch(w -> w > 0.0)) {
            this.warn(path, "all special events are disabled; enabling all of them");
            for (SpecialEventType type : SpecialEventType.values()) {
                weights.put(type, 1.0);
            }
        }
        return weights;
    }

    /** Supply tier -> probability in percent; validated like the mob tables. */
    private WeightedTable<SupplyTier> tierTable() {
        String path = "supply.tiers";
        Map<SupplyTier, Double> defaults = new LinkedHashMap<>();
        defaults.put(SupplyTier.LEGENDARY, 15.0);
        defaults.put(SupplyTier.RARE, 35.0);
        defaults.put(SupplyTier.COMMON, 50.0);
        ConfigurationSection section = this.file.getConfigurationSection(path);
        if (section == null) {
            return WeightedTable.of(defaults);
        }
        Map<SupplyTier, Double> weights = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            SupplyTier tier = SupplyTier.parse(key).orElse(null);
            double weight = section.getDouble(key, -1.0);
            if (tier == null) {
                this.warn(path + "." + key, "unknown tier (use LEGENDARY, RARE or COMMON)");
            } else if (weight < 0.0) {
                this.warn(path + "." + key, "probability must be a number >= 0");
            } else {
                weights.put(tier, weight);
            }
        }
        return this.percentTable(path, weights, defaults);
    }

    /** Lucky block outcome -> probability in percent. */
    private WeightedTable<LuckyOutcome> outcomeTable() {
        String path = "supply.outcomes";
        Map<LuckyOutcome, Double> defaults = new LinkedHashMap<>();
        defaults.put(LuckyOutcome.GOOD, 60.0);
        defaults.put(LuckyOutcome.NEUTRAL, 15.0);
        defaults.put(LuckyOutcome.BAD, 25.0);
        ConfigurationSection section = this.file.getConfigurationSection(path);
        if (section == null) {
            return WeightedTable.of(defaults);
        }
        Map<LuckyOutcome, Double> weights = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            LuckyOutcome outcome = LuckyOutcome.parse(key).orElse(null);
            double weight = section.getDouble(key, -1.0);
            if (outcome == null) {
                this.warn(path + "." + key, "unknown outcome (use GOOD, NEUTRAL or BAD)");
            } else if (weight < 0.0) {
                this.warn(path + "." + key, "probability must be a number >= 0");
            } else {
                weights.put(outcome, weight);
            }
        }
        return this.percentTable(path, weights, defaults);
    }

    /** The lucky block material: a full solid block. */
    private Material luckyBlockType() {
        String path = "supply.lucky-block";
        String raw = this.file.getString(path, "RAW_GOLD_BLOCK");
        Material material = Material.matchMaterial(raw.trim());
        if (material == null || !material.isBlock() || !material.isSolid() || !material.isOccluding()) {
            this.warn(path, "'" + raw + "' is not a full solid block; using RAW_GOLD_BLOCK");
            return Material.RAW_GOLD_BLOCK;
        }
        return material;
    }

    private NamedTextColor glowColor() {
        String raw = this.file.getString("effects.glow-color", "aqua");
        NamedTextColor color = NamedTextColor.NAMES.value(raw.trim().toLowerCase(Locale.ROOT));
        if (color == null) {
            this.warn("effects.glow-color", "'" + raw + "' is not a colour name (e.g. aqua, gold, light_purple); using aqua");
            return NamedTextColor.AQUA;
        }
        return color;
    }

    // --- accessors -------------------------------------------------------------------------------------------

    public boolean autoStart() {
        return this.autoStart;
    }

    /** Name of the lobby world players return to when the game stops; empty means the main world. */
    public String lobbyWorld() {
        return this.lobbyWorld;
    }

    public OptionalLong seed() {
        return this.seed;
    }

    public int largeAreaFocusRadius() {
        return this.largeAreaFocusRadius;
    }

    public int worldEditBlocksPerTick() {
        return this.worldEditBlocksPerTick;
    }

    public Rotation rotation() {
        return this.rotation;
    }

    public OverworldSettings overworld() {
        return this.overworld;
    }

    public NetherSettings nether() {
        return this.nether;
    }

    /** Whether the playable area is closed by a world border (off by default: players may fly out). */
    public boolean worldBorder() {
        return this.worldBorder;
    }

    public AncientCitySettings ancientCity() {
        return this.ancientCity;
    }

    public EndSettings end() {
        return this.end;
    }

    public MobEventSettings mobEvents() {
        return this.mobEvents;
    }

    public SpecialEventSettings specialEvents() {
        return this.specialEvents;
    }

    public SupplySettings supply() {
        return this.supply;
    }

    public EffectsSettings effects() {
        return this.effects;
    }

    public ScoreboardSettings scoreboard() {
        return this.scoreboard;
    }

    public int warningCount() {
        return this.warnings;
    }
}
