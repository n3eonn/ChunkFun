package dev.chunkfun.game;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.effects.Effects;
import dev.chunkfun.supply.SupplyTier;
import dev.chunkfun.util.Text;
import dev.chunkfun.world.Environment;
import dev.chunkfun.world.EnvironmentBuilder;
import dev.chunkfun.world.EnvironmentType;
import dev.chunkfun.world.EnvironmentVariant;
import dev.chunkfun.world.PlayableArea;
import dev.chunkfun.world.SurfaceScanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/**
 * The game's state machine and clock.
 *
 * <p>A single repeating task ticks once per second and drives every timer: the chunk rotation (with its
 * countdown), mob events, special events and item supplies. Each environment is prepared ahead of time,
 * so the countdown only switches players over to an environment that is already generated and verified.</p>
 *
 * <p>All methods must be called on the main thread, except the {@code volatile} getters used by the
 * async spawn-location event.</p>
 */
public final class GameManager {

    public enum State {
        STOPPED,
        STARTING,
        RUNNING,
        TRANSITION,
        STOPPING
    }

    private static final int MAX_PREPARATION_ATTEMPTS = 3;
    /** Players cannot be hurt for this long after arriving in an environment or respawning (except by the void). */
    private static final int ARRIVAL_PROTECTION_TICKS = 60;

    /** One in-flight environment preparation. */
    private static final class Preparation {
        final long generation;
        final int rotationIndex;
        final EnvironmentType type;
        EnvironmentVariant variant;
        int attempt;
        boolean abandoned;
        CompletableFuture<Environment> future;
        BukkitTask timeout;

        Preparation(long generation, int rotationIndex, EnvironmentType type, EnvironmentVariant variant) {
            this.generation = generation;
            this.rotationIndex = rotationIndex;
            this.type = type;
            this.variant = variant;
        }
    }

    private final ChunkFunPlugin plugin;
    private final EnvironmentBuilder builder;
    private final Random random = new Random();

    private volatile State state = State.STOPPED;
    private volatile Environment active;
    private Environment next;
    private int nextRotationIndex = -1;
    private Preparation preparation;
    private long generation;
    private int rotationIndex;
    private int rotationRemaining;
    private int mobRemaining;
    private int specialRemaining;
    private int supplyRemaining;
    private int waitingSeconds;
    private BukkitTask clock;
    private final Map<UUID, Integer> protectedUntil = new HashMap<>();

    public GameManager(ChunkFunPlugin plugin) {
        this.plugin = plugin;
        this.builder = new EnvironmentBuilder(plugin);
    }

    private ChunkFunConfig config() {
        return this.plugin.settings();
    }

    // --- lifecycle -------------------------------------------------------------------------------------------

    /** Starts the game by preparing the first environment of the rotation. */
    public boolean start(CommandSender initiator) {
        if (this.state != State.STOPPED) {
            return false;
        }
        this.generation++;
        this.state = State.STARTING;
        this.rotationIndex = 0;
        EnvironmentType firstType = this.config().rotation().order().getFirst();
        Text.broadcast(Text.info("Starting ChunkFun - preparing the " + firstType.name().toLowerCase() + " chunk..."));
        this.prepare(0, firstType);
        this.ensureClock();
        if (initiator != null) {
            this.plugin.getLogger().info("Game start requested by " + initiator.getName());
        }
        return true;
    }

    /** Stops the game, returns everybody to the lobby and deletes all ChunkFun worlds. */
    public boolean stop(@Nullable CommandSender initiator) {
        if (this.state == State.STOPPED || this.state == State.STOPPING) {
            return false;
        }
        this.state = State.STOPPING;
        this.generation++;
        this.plugin.specialEvents().stopActive(false);
        this.plugin.supplies().clearAll();
        this.abandonPreparation();

        Location lobby = this.plugin.lobbySpawn();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (ChunkFunPlugin.isChunkFunWorld(player.getWorld()) && !player.isDead()) {
                player.closeInventory();
                player.leaveVehicle();
                player.teleportAsync(lobby, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        }
        if (this.next != null) {
            this.plugin.janitor().retire(this.next, this.plugin::lobbySpawn, null);
            this.next = null;
        }
        if (this.active != null) {
            this.plugin.janitor().retire(this.active, this.plugin::lobbySpawn, null);
            this.active = null;
        }
        if (this.clock != null) {
            this.clock.cancel();
            this.clock = null;
        }
        this.state = State.STOPPED;
        Text.broadcast(Text.info("ChunkFun has been stopped."));
        if (initiator != null) {
            this.plugin.getLogger().info("Game stopped by " + initiator.getName());
        }
        return true;
    }

    /** Synchronous cleanup for plugin shutdown. */
    public void shutdown() {
        this.generation++;
        this.state = State.STOPPING;
        try {
            this.plugin.specialEvents().stopActive(true);
            this.plugin.supplies().clearAll();
        } catch (RuntimeException ex) {
            this.plugin.getLogger().log(Level.WARNING, "Error while ending events during shutdown", ex);
        }
        this.abandonPreparation();
        if (this.clock != null) {
            this.clock.cancel();
            this.clock = null;
        }
        Location lobby = this.plugin.lobbySpawn();
        List<Environment> environments = new ArrayList<>();
        if (this.active != null) {
            environments.add(this.active);
        }
        if (this.next != null) {
            environments.add(this.next);
        }
        for (Environment environment : environments) {
            try {
                this.plugin.janitor().retireNow(environment, lobby);
            } catch (RuntimeException ex) {
                this.plugin.getLogger().log(Level.WARNING, "Could not retire " + environment, ex);
            }
        }
        // Worlds still being prepared are not referenced yet; unload any remaining ChunkFun world as well.
        for (World world : new ArrayList<>(Bukkit.getWorlds())) {
            if (ChunkFunPlugin.isChunkFunWorld(world)) {
                for (Player player : world.getPlayers()) {
                    player.teleport(lobby, PlayerTeleportEvent.TeleportCause.PLUGIN);
                }
                Bukkit.unloadWorld(world, false);
            }
        }
        this.active = null;
        this.next = null;
        this.state = State.STOPPED;
    }

    private void ensureClock() {
        if (this.clock == null) {
            this.clock = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tickSecond, 20L, 20L);
        }
    }

    // --- preparation -----------------------------------------------------------------------------------------

    private EnvironmentVariant resolveVariant(EnvironmentType type) {
        return switch (type) {
            case OVERWORLD -> EnvironmentVariant.OVERWORLD;
            case NETHER -> EnvironmentVariant.NETHER;
            case ANCIENT_CITY -> EnvironmentVariant.ANCIENT_CITY;
            // The 25% roll happens exactly once, when a new End rotation is generated.
            case END -> this.random.nextDouble() < this.config().end().islandChance()
                    ? EnvironmentVariant.END_ISLAND
                    : EnvironmentVariant.END_CITY;
        };
    }

    private void prepare(int index, EnvironmentType type) {
        Preparation prep = new Preparation(this.generation, index, type, this.resolveVariant(type));
        this.preparation = prep;
        this.plugin.getLogger().info("Preparing next environment: " + prep.variant);
        this.launch(prep);
    }

    private void launch(Preparation prep) {
        prep.attempt++;
        CompletableFuture<Environment> future;
        try {
            future = this.builder.build(prep.variant);
        } catch (RuntimeException ex) {
            future = CompletableFuture.failedFuture(ex);
        }
        prep.future = future;
        int timeoutTicks = this.config().rotation().preparationTimeoutSeconds() * 20;
        prep.timeout = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (this.preparation == prep && !prep.future.isDone()) {
                this.plugin.getLogger().warning("Preparing " + prep.variant + " timed out.");
                this.onPreparationFailed(prep, new IllegalStateException("preparation timed out"));
            }
        }, timeoutTicks);
        CompletableFuture<Environment> attemptFuture = future;
        future.whenComplete((environment, error) -> this.runOnMain(() -> this.onPrepared(prep, attemptFuture, environment, error)));
    }

    private void onPrepared(Preparation prep, CompletableFuture<Environment> attempt, Environment environment, Throwable error) {
        boolean current = this.preparation == prep && prep.future == attempt && !prep.abandoned
                && prep.generation == this.generation;
        if (!current) {
            // Stopped, superseded or timed out: throw the result away.
            if (environment != null) {
                this.builder.discard(environment);
            }
            return;
        }
        if (prep.timeout != null) {
            prep.timeout.cancel();
        }
        if (error != null || environment == null) {
            this.onPreparationFailed(prep, error == null ? new IllegalStateException("no environment") : error);
            return;
        }
        this.preparation = null;
        if (this.state == State.STARTING) {
            this.rotationIndex = prep.rotationIndex;
            this.activateFirst(environment);
        } else if (this.state == State.RUNNING || this.state == State.TRANSITION) {
            this.next = environment;
            this.nextRotationIndex = prep.rotationIndex;
            this.plugin.getLogger().info("Next environment ready: " + environment.variant() + " - " + environment.description());
        } else {
            this.builder.discard(environment);
        }
    }

    private void onPreparationFailed(Preparation prep, Throwable error) {
        if (prep.timeout != null) {
            prep.timeout.cancel();
        }
        this.plugin.getLogger().log(Level.SEVERE, "Preparing " + prep.variant + " failed (attempt " + prep.attempt + ")", error);
        if (prep.attempt < MAX_PREPARATION_ATTEMPTS && this.state != State.STOPPED && this.state != State.STOPPING) {
            // Last attempt for an End rotation switches to the other End variant.
            if (prep.attempt == MAX_PREPARATION_ATTEMPTS - 1 && prep.type == EnvironmentType.END) {
                prep.variant = prep.variant == EnvironmentVariant.END_CITY ? EnvironmentVariant.END_ISLAND : EnvironmentVariant.END_CITY;
            }
            this.launch(prep);
            return;
        }
        this.preparation = null;
        if (this.state == State.STARTING) {
            this.state = State.STOPPED;
            if (this.clock != null) {
                this.clock.cancel();
                this.clock = null;
            }
            Text.broadcast(Text.error("ChunkFun could not generate its first environment. Check the console."));
        }
        // While running, the countdown notices the missing environment and postpones the rotation.
    }

    private void abandonPreparation() {
        Preparation prep = this.preparation;
        if (prep != null) {
            prep.abandoned = true;
            if (prep.timeout != null) {
                prep.timeout.cancel();
            }
            this.preparation = null;
        }
    }

    private void runOnMain(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else if (this.plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(this.plugin, runnable);
        }
    }

    // --- clock -----------------------------------------------------------------------------------------------

    private void tickSecond() {
        try {
            this.plugin.refreshLobbySpawn();
            switch (this.state) {
                case RUNNING -> this.tickRunning();
                case TRANSITION -> this.tickTransition();
                default -> {
                }
            }
        } catch (RuntimeException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "Error in the ChunkFun game clock", ex);
        }
    }

    private void tickRunning() {
        Environment current = this.active;
        if (current == null || Bukkit.getWorld(current.world().getUID()) == null) {
            this.plugin.getLogger().severe("The active environment disappeared; stopping the game.");
            this.stop(null);
            return;
        }
        ChunkFunConfig.Rotation rotation = this.config().rotation();
        this.rotationRemaining--;
        if (this.next == null && this.preparation == null && this.rotationRemaining <= rotation.prepareAheadSeconds()) {
            int index = this.followingIndex();
            this.prepare(index, rotation.order().get(index));
        }
        if (this.rotationRemaining <= rotation.countdownSeconds()) {
            this.beginTransition();
            return;
        }

        this.mobRemaining = Math.max(0, this.mobRemaining - 1);
        if (this.mobRemaining == 0) {
            this.plugin.mobEvents().trigger(current);
            this.mobRemaining = this.config().mobEvents().intervalSeconds();
        }

        this.specialRemaining = Math.max(0, this.specialRemaining - 1);
        if (this.specialRemaining == 0 && !this.plugin.specialEvents().isActive()) {
            this.plugin.specialEvents().startRandom(current);
            this.specialRemaining = this.config().specialEvents().intervalSeconds();
        }

        this.supplyRemaining = Math.max(0, this.supplyRemaining - 1);
        if (this.supplyRemaining == 0) {
            this.plugin.supplies().spawnSupply(current);
            this.supplyRemaining = this.randomSupplyDelay();
        }
    }

    private void beginTransition() {
        this.state = State.TRANSITION;
        this.waitingSeconds = 0;
        // Special events never cross an environment change: end (and restore) the current one now.
        this.plugin.specialEvents().stopActive(false);
        if (this.next == null && this.preparation == null) {
            int index = this.followingIndex();
            this.prepare(index, this.config().rotation().order().get(index));
        }
        String target = this.upcomingName();
        Text.broadcast(Text.prefixed(Component.text("The environment is changing", NamedTextColor.YELLOW)
                .append(Component.text(target == null ? "!" : " to " + target + "!", NamedTextColor.GOLD))));
        Text.everyone().playSound(Text.sound("block.beacon.deactivate", 1.0f, 0.8f));
        this.showCountdown(Math.max(1, this.rotationRemaining));
    }

    private void tickTransition() {
        if (this.rotationRemaining > 1) {
            this.rotationRemaining--;
            this.showCountdown(this.rotationRemaining);
            return;
        }
        this.rotationRemaining = 0;
        Environment ready = this.next;
        if (ready != null && ready.isUsable() && Bukkit.getWorld(ready.world().getUID()) != null) {
            this.switchTo(ready);
            return;
        }
        if (this.preparation != null) {
            this.waitingSeconds++;
            Component wait = Component.text("Preparing the next environment... (" + this.waitingSeconds + "s)", NamedTextColor.YELLOW);
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendActionBar(wait);
            }
            return;
        }
        this.postponeRotation();
    }

    private void showCountdown(int seconds) {
        String target = this.upcomingName();
        Component title = Component.text(String.valueOf(seconds),
                seconds <= 3 ? NamedTextColor.RED : NamedTextColor.GOLD, TextDecoration.BOLD);
        Component subtitle = Component.text(target == null ? "Changing environment" : "Next: " + target, NamedTextColor.YELLOW);
        float pitch = 0.6f + (this.config().rotation().countdownSeconds() - seconds) * 0.15f;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Text.title(title, subtitle, 0, 25, 5));
            player.playSound(Text.sound("block.note_block.pling", 1.0f, Math.max(0.5f, Math.min(2.0f, pitch))));
            Effects.ring(player, seconds <= 3 ? Particle.SOUL_FIRE_FLAME : Particle.PORTAL, 1.2 + seconds * 0.25, 24);
        }
    }

    private void postponeRotation() {
        this.state = State.RUNNING;
        this.rotationRemaining = this.durationFor(this.active.variant());
        Text.broadcast(Text.error("The next environment could not be generated; staying here for another round."));
    }

    private void switchTo(Environment environment) {
        Environment previous = this.active;
        this.active = environment;
        this.next = null;
        environment.phase(Environment.Phase.ACTIVE);
        this.rotationIndex = this.nextRotationIndex >= 0 ? this.nextRotationIndex : this.followingIndex();
        this.nextRotationIndex = -1;
        this.rotationRemaining = this.durationFor(environment.variant());
        this.state = State.RUNNING;

        this.verifySpawn(environment);
        this.teleportAll(environment);
        this.announceArrival(environment);
        this.plugin.scoreboards().refreshAll();

        if (previous != null) {
            this.plugin.supplies().forgetEnvironment(previous);
            // Give the teleports a moment; the janitor evacuates stragglers and retries until the world is empty.
            Bukkit.getScheduler().runTaskLater(this.plugin,
                    () -> this.plugin.janitor().retire(previous, this::evacuationTarget, null), 40L);
        }
    }

    private void activateFirst(Environment environment) {
        this.active = environment;
        environment.phase(Environment.Phase.ACTIVE);
        this.rotationRemaining = this.durationFor(environment.variant());
        this.mobRemaining = this.config().mobEvents().intervalSeconds();
        this.specialRemaining = this.config().specialEvents().intervalSeconds();
        this.supplyRemaining = this.randomSupplyDelay();
        this.state = State.RUNNING;
        this.verifySpawn(environment);
        this.teleportAll(environment);
        this.announceArrival(environment);
        this.plugin.scoreboards().refreshAll();
    }

    private void announceArrival(Environment environment) {
        EnvironmentVariant variant = environment.variant();
        Component title = Component.text(variant.displayName(), variant.color(), TextDecoration.BOLD);
        Component subtitle = Component.text(environment.description(), NamedTextColor.GRAY);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Text.title(title, subtitle, 10, 60, 20));
            player.playSound(Text.sound("entity.player.levelup", 1.0f, 1.0f));
        }
        String minutes = String.valueOf(this.durationFor(variant) / 60);
        Text.broadcast(Text.prefixed(Component.text("Welcome to the ", NamedTextColor.GRAY)
                .append(Component.text(variant.displayName(), variant.color()))
                .append(Component.text(" (" + environment.description() + "). Next change in " + minutes + " minutes.",
                        NamedTextColor.GRAY))));
    }

    // --- players ---------------------------------------------------------------------------------------------

    private void teleportAll(Environment environment) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.sendToEnvironment(player, environment, 0);
        }
    }

    /**
     * Safely moves a player into an environment. Dead players are skipped; the respawn handler routes them.
     * The destination chunk is held loaded by a plugin ticket, so the teleport never lands in an unloaded chunk.
     */
    public void sendToEnvironment(Player player, Environment environment, int attempt) {
        if (!player.isOnline() || player.isDead() || !environment.isUsable()) {
            return;
        }
        Location spawn = environment.spawn();
        // Closing the inventory first returns the cursor item and detaches any container of the old world.
        player.closeInventory();
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        player.eject();
        player.teleportAsync(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, error) -> this.runOnMain(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (Boolean.TRUE.equals(success)) {
                player.setFallDistance(0.0f);
                player.setFireTicks(0);
                this.protect(player);
                Effects.arrival(player);
            } else if (attempt < 3 && this.active == environment) {
                Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.sendToEnvironment(player, environment, attempt + 1), 10L);
            } else if (attempt >= 3) {
                this.plugin.getLogger().warning("Could not teleport " + player.getName() + " into " + environment);
            }
        }));
    }

    /** Called when a player joins (sync). */
    public void handleJoin(Player player) {
        Environment current = this.active;
        boolean running = this.state == State.RUNNING || this.state == State.TRANSITION;
        if (running && current != null && current.isUsable()) {
            if (!current.owns(player.getWorld())) {
                this.sendToEnvironment(player, current, 0);
            }
        } else if (ChunkFunPlugin.isChunkFunWorld(player.getWorld())) {
            player.teleportAsync(this.plugin.lobbySpawn(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    /**
     * Spawn location override for joining players, called from the async spawn-location event.
     * Only reads volatile fields and immutable copies.
     */
    public @Nullable Location joinSpawnFor(Location current) {
        Environment environment = this.active;
        State currentState = this.state;
        boolean running = currentState == State.RUNNING || currentState == State.TRANSITION;
        World world = current == null ? null : current.getWorld();
        if (running && environment != null && environment.isUsable()) {
            return environment.owns(world) ? null : environment.spawn();
        }
        if (world == null || ChunkFunPlugin.isChunkFunWorld(world)) {
            return this.plugin.cachedLobbySpawn();
        }
        return null;
    }

    /** Respawn routing: a bed or anchor in the active environment is honoured, everything else goes to its spawn. */
    public void handleRespawn(PlayerRespawnEvent event) {
        Environment current = this.active;
        boolean running = this.state == State.RUNNING || this.state == State.TRANSITION;
        Location respawn = event.getRespawnLocation();
        if (running && current != null && current.isUsable()) {
            if (!current.owns(respawn.getWorld())) {
                event.setRespawnLocation(current.spawn());
            }
            this.protect(event.getPlayer());
        } else if (ChunkFunPlugin.isChunkFunWorld(respawn.getWorld())) {
            event.setRespawnLocation(this.plugin.lobbySpawn());
        }
    }

    private void protect(Player player) {
        this.protectedUntil.put(player.getUniqueId(), Bukkit.getCurrentTick() + ARRIVAL_PROTECTION_TICKS);
    }

    /** Whether the player is inside the short damage-immunity window after arriving or respawning. */
    public boolean isProtected(Player player) {
        Integer until = this.protectedUntil.get(player.getUniqueId());
        if (until == null) {
            return false;
        }
        if (Bukkit.getCurrentTick() > until) {
            this.protectedUntil.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public void forget(Player player) {
        this.protectedUntil.remove(player.getUniqueId());
    }

    /**
     * The spawn was computed when the environment was prepared; make sure nothing (a mob, fluid, falling
     * block) changed it before players arrive, and pick a new one if it did.
     */
    private void verifySpawn(Environment environment) {
        Location spawn = environment.spawn();
        if (spawn != null && SurfaceScanner.isStillSafe(spawn)) {
            return;
        }
        PlayableArea area = environment.area();
        double preferX = spawn == null ? area.centerX() : spawn.getX();
        double preferZ = spawn == null ? area.centerZ() : spawn.getZ();
        Location fresh = SurfaceScanner.findPlayerSpawn(area, preferX, preferZ, 0.0);
        if (fresh == null) {
            fresh = SurfaceScanner.buildEmergencyPlatform(area, environment.variant().fallbackFloor());
        }
        environment.spawn(fresh);
        environment.world().setSpawnLocation(fresh);
        environment.world().addPluginChunkTicket(fresh.getBlockX() >> 4, fresh.getBlockZ() >> 4, this.plugin);
        this.plugin.getLogger().info("Spawn of " + environment + " was no longer safe; moved it.");
    }

    private Location evacuationTarget() {
        Environment current = this.active;
        if (current != null && current.isUsable() && (this.state == State.RUNNING || this.state == State.TRANSITION)) {
            return current.spawn();
        }
        return this.plugin.lobbySpawn();
    }

    // --- admin actions ---------------------------------------------------------------------------------------

    /** Starts the countdown to the next environment immediately. */
    public boolean forceNext() {
        if (this.state != State.RUNNING) {
            return false;
        }
        this.rotationRemaining = this.config().rotation().countdownSeconds();
        this.beginTransition();
        return true;
    }

    public boolean triggerMobEvent() {
        if (this.state != State.RUNNING || this.active == null) {
            return false;
        }
        this.plugin.mobEvents().trigger(this.active);
        this.mobRemaining = this.config().mobEvents().intervalSeconds();
        return true;
    }

    /** @param tier forces every category to this tier, or {@code null} to roll per category */
    public boolean triggerSupply(@Nullable SupplyTier tier) {
        if (this.state != State.RUNNING || this.active == null) {
            return false;
        }
        this.plugin.supplies().spawnSupply(this.active, tier);
        this.supplyRemaining = this.randomSupplyDelay();
        return true;
    }

    public boolean isRunning() {
        return this.state == State.RUNNING || this.state == State.TRANSITION;
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private int followingIndex() {
        return (this.rotationIndex + 1) % this.config().rotation().order().size();
    }

    private int durationFor(EnvironmentVariant variant) {
        return variant == EnvironmentVariant.END_ISLAND
                ? this.config().end().islandDurationSeconds()
                : this.config().rotation().durationSeconds();
    }

    private int randomSupplyDelay() {
        ChunkFunConfig.SupplySettings supply = this.config().supply();
        return supply.minIntervalSeconds() + this.random.nextInt(supply.maxIntervalSeconds() - supply.minIntervalSeconds() + 1);
    }

    /** Display name of the environment the rotation is heading to, if already decided. */
    private @Nullable String upcomingName() {
        if (this.next != null) {
            return this.next.variant().displayName();
        }
        if (this.preparation != null) {
            return this.preparation.variant.displayName();
        }
        return null;
    }

    public State state() {
        return this.state;
    }

    public @Nullable Environment activeEnvironment() {
        return this.active;
    }

    public @Nullable Environment nextEnvironment() {
        return this.next;
    }

    public @Nullable String upcomingEnvironmentName() {
        return this.upcomingName();
    }

    public boolean isPreparing() {
        return this.preparation != null;
    }

    public int secondsUntilRotation() {
        return Math.max(0, this.rotationRemaining);
    }

    public int secondsUntilMobEvent() {
        return Math.max(0, this.mobRemaining);
    }

    public int secondsUntilSpecialEvent() {
        return Math.max(0, this.specialRemaining);
    }

    public int secondsUntilSupply() {
        return Math.max(0, this.supplyRemaining);
    }
}
