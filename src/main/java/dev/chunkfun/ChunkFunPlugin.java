package dev.chunkfun;

import dev.chunkfun.command.ChunkFunCommand;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.effects.EffectsService;
import dev.chunkfun.events.mob.MobEventManager;
import dev.chunkfun.events.special.SpecialEventManager;
import dev.chunkfun.game.GameManager;
import dev.chunkfun.listener.BoundaryListener;
import dev.chunkfun.listener.PlayerListener;
import dev.chunkfun.scoreboard.ScoreboardService;
import dev.chunkfun.supply.SupplyManager;
import dev.chunkfun.util.Keys;
import dev.chunkfun.world.WorldJanitor;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Entry point. Wires the subsystems together and owns their lifecycle. */
public final class ChunkFunPlugin extends JavaPlugin {

    /** Namespace of every world ChunkFun creates. */
    public static final String WORLD_NAMESPACE = "chunkfun";

    private ChunkFunConfig settings;
    private WorldJanitor janitor;
    private GameManager game;
    private MobEventManager mobEvents;
    private SpecialEventManager specialEvents;
    private SupplyManager supplies;
    private ScoreboardService scoreboards;
    private EffectsService effects;
    private volatile Location cachedLobbySpawn;

    @Override
    public void onEnable() {
        Keys.init(this);
        this.saveDefaultConfig();
        this.reloadConfig();
        this.migrateConfig();
        this.settings = ChunkFunConfig.load(this.getConfig(), this.getLogger());

        this.janitor = new WorldJanitor(this);
        this.janitor.cleanupLeftovers();

        this.mobEvents = new MobEventManager(this);
        this.specialEvents = new SpecialEventManager(this);
        this.supplies = new SupplyManager(this);
        this.game = new GameManager(this);
        this.scoreboards = new ScoreboardService(this);
        this.effects = new EffectsService(this);

        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BoundaryListener(this), this);
        Bukkit.getPluginManager().registerEvents(this.supplies, this);
        Bukkit.getPluginManager().registerEvents(this.effects, this);

        this.registerCommand("chunkfun", "ChunkFun minigame administration", List.of("cf"), new ChunkFunCommand(this));

        this.refreshLobbySpawn();
        this.scoreboards.start();
        this.supplies.start();
        this.effects.start();

        if (this.settings.autoStart()) {
            // Wait one tick so every world and plugin is fully enabled.
            Bukkit.getScheduler().runTask(this, () -> this.game.start(Bukkit.getConsoleSender()));
        }
    }

    @Override
    public void onDisable() {
        if (this.game != null) {
            this.game.shutdown();
        }
        if (this.effects != null) {
            this.effects.stop();
        }
        if (this.scoreboards != null) {
            this.scoreboards.shutdown();
        }
        Bukkit.getScheduler().cancelTasks(this);
    }

    /**
     * Upgrades config files written by older versions. Values still at an old default move to the new default;
     * values the admin changed are kept. Keys that did not exist yet are added from the bundled config.
     * <ul>
     *     <li>v2: faster rotation and events, Ancient City in the rotation.</li>
     *     <li>v3: lucky blocks replace loot shulkers; nerfed tier odds.</li>
     * </ul>
     */
    private void migrateConfig() {
        FileConfiguration config = this.getConfig();
        int version = config.getInt("config-version", 1);
        if (version >= 3) {
            return;
        }
        if (version < 2) {
            replaceIfDefault(config, "rotation.order", List.of("OVERWORLD", "NETHER", "END"),
                    List.of("OVERWORLD", "NETHER", "END", "ANCIENT_CITY"));
            replaceIfDefault(config, "rotation.duration-seconds", 900, 480);
            replaceIfDefault(config, "rotation.prepare-ahead-seconds", 90, 60);
            replaceIfDefault(config, "end.island-duration-seconds", 1200, 720);
            replaceIfDefault(config, "mob-events.interval-seconds", 120, 60);
            replaceIfDefault(config, "special-events.interval-seconds", 300, 120);
            replaceIfDefault(config, "supply.min-interval-seconds", 60, 30);
            replaceIfDefault(config, "supply.max-interval-seconds", 300, 150);
        }
        // v3: lucky blocks.
        if (config.isSet("supply.shulker-count") && !config.isSet("supply.lucky-block-count")) {
            config.set("supply.lucky-block-count", config.getInt("supply.shulker-count"));
        }
        if (config.isSet("supply.labels") && !config.isSet("supply.holograms")) {
            config.set("supply.holograms", config.getBoolean("supply.labels"));
        }
        config.set("supply.shulker-count", null);
        config.set("supply.labels", null);
        config.set("supply.colors", null);
        if (config.getInt("supply.tiers.LEGENDARY", -1) == 30 && config.getInt("supply.tiers.RARE", -1) == 35
                && config.getInt("supply.tiers.COMMON", -1) == 35) {
            config.set("supply.tiers.LEGENDARY", 15);
            config.set("supply.tiers.COMMON", 50);
        }
        config.set("config-version", 3);
        config.options().copyDefaults(true);
        this.saveConfig();
        // Read the saved file back so new sections are real values, not default-backed placeholders.
        this.reloadConfig();
        this.getLogger().info("Updated config.yml to version 3 (lucky blocks, effects, nerfed loot).");
    }

    private static void replaceIfDefault(FileConfiguration config, String path, Object oldDefault, Object newDefault) {
        Object current = config.isList(path) ? config.getStringList(path) : config.get(path);
        if (current == null || current.equals(oldDefault)) {
            config.set(path, newDefault);
        }
    }

    /** Reloads config.yml. Running timers keep their current countdown and use the new values afterwards. */
    public ChunkFunConfig reloadSettings() {
        this.reloadConfig();
        this.settings = ChunkFunConfig.load(this.getConfig(), this.getLogger());
        this.refreshLobbySpawn();
        this.scoreboards.restart();
        this.effects.start();
        return this.settings;
    }

    public ChunkFunConfig settings() {
        return this.settings;
    }

    public WorldJanitor janitor() {
        return this.janitor;
    }

    public GameManager game() {
        return this.game;
    }

    public MobEventManager mobEvents() {
        return this.mobEvents;
    }

    public SpecialEventManager specialEvents() {
        return this.specialEvents;
    }

    public SupplyManager supplies() {
        return this.supplies;
    }

    public EffectsService effects() {
        return this.effects;
    }

    public ScoreboardService scoreboards() {
        return this.scoreboards;
    }

    /** The world players return to when the game is not running. */
    public World lobbyWorld() {
        String name = this.settings == null ? "" : this.settings.lobbyWorld();
        if (name != null && !name.isBlank()) {
            World configured = Bukkit.getWorld(name);
            if (configured != null && !isChunkFunWorld(configured)) {
                return configured;
            }
        }
        return Bukkit.getWorlds().getFirst();
    }

    /** Main-thread only. */
    public Location lobbySpawn() {
        Location spawn = this.lobbyWorld().getSpawnLocation();
        this.cachedLobbySpawn = spawn.clone();
        return spawn;
    }

    /** Thread-safe copy of the last known lobby spawn, for async events. */
    public Location cachedLobbySpawn() {
        Location cached = this.cachedLobbySpawn;
        return cached == null ? null : cached.clone();
    }

    public void refreshLobbySpawn() {
        this.lobbySpawn();
    }

    public static boolean isChunkFunWorld(World world) {
        return world != null && WORLD_NAMESPACE.equals(world.getKey().getNamespace());
    }
}
