package dev.chunkfun.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Unloads retired environments and deletes their folders.
 *
 * <p>Every created world folder is recorded in a ledger file before anything is generated, so worlds
 * left behind by a crash are removed on the next start. Deletion only ever touches folders that pass
 * {@link #isSafeToDelete(Path)}.</p>
 */
public final class WorldJanitor {

    private static final String MARKER = "chunkfun";
    private static final int MAX_UNLOAD_ATTEMPTS = 120;

    private final JavaPlugin plugin;
    private final Path ledger;
    private final Set<String> tracked = new LinkedHashSet<>();

    public WorldJanitor(JavaPlugin plugin) {
        this.plugin = plugin;
        this.ledger = plugin.getDataFolder().toPath().resolve("managed-worlds.txt");
        this.readLedger();
    }

    /** Records a world folder so it is cleaned up even if the server crashes. */
    public synchronized void register(Path folder) {
        this.tracked.add(folder.toAbsolutePath().normalize().toString());
        this.writeLedger();
    }

    private synchronized void forget(Path folder) {
        this.tracked.remove(folder.toAbsolutePath().normalize().toString());
        this.writeLedger();
    }

    /** Deletes folders of worlds left over from a previous run. Called on enable, before any world is created. */
    public void cleanupLeftovers() {
        List<Path> leftovers;
        synchronized (this) {
            leftovers = this.tracked.stream().map(Path::of).toList();
        }
        for (Path folder : leftovers) {
            if (this.isLoadedWorldFolder(folder)) {
                continue;
            }
            if (!Files.exists(folder)) {
                this.forget(folder);
                continue;
            }
            this.plugin.getLogger().info("Removing leftover ChunkFun world " + folder);
            this.deleteAsync(folder);
        }
    }

    /**
     * Retires an environment: evacuates remaining players, removes tickets, unloads without saving
     * and deletes the folder. Retries while players (for example dead players on the respawn screen)
     * are still inside.
     *
     * @param evacuation supplies where stragglers are sent; called on the main thread
     */
    public void retire(Environment environment, Supplier<Location> evacuation, Runnable onUnloaded) {
        if (environment.phase() == Environment.Phase.RETIRED || environment.phase() == Environment.Phase.RETIRING) {
            return;
        }
        environment.phase(Environment.Phase.RETIRING);
        World world = environment.world();
        world.removePluginChunkTickets(this.plugin);

        new BukkitRunnable() {
            private int attempts;

            @Override
            public void run() {
                this.attempts++;
                if (Bukkit.getWorld(world.getUID()) == null) {
                    // Already gone (unloaded elsewhere).
                    this.finish(true);
                    return;
                }
                List<Player> inside = new ArrayList<>(world.getPlayers());
                if (!inside.isEmpty()) {
                    Location target = evacuation.get();
                    for (Player player : inside) {
                        evacuate(player, target);
                    }
                    if (this.attempts < MAX_UNLOAD_ATTEMPTS) {
                        return;
                    }
                }
                boolean unloaded = Bukkit.unloadWorld(world, false);
                if (unloaded) {
                    this.finish(true);
                } else if (this.attempts >= MAX_UNLOAD_ATTEMPTS) {
                    plugin.getLogger().warning("Could not unload " + world.getName() + " after " + this.attempts
                            + " attempts; it will be removed on the next server start.");
                    this.finish(false);
                }
            }

            private void finish(boolean unloaded) {
                this.cancel();
                environment.phase(Environment.Phase.RETIRED);
                if (unloaded) {
                    deleteAsync(environment.folder());
                }
                if (onUnloaded != null) {
                    onUnloaded.run();
                }
            }
        }.runTaskTimer(this.plugin, 1L, 20L);
    }

    /**
     * Synchronous variant for plugin shutdown. Players are teleported synchronously, the world is
     * unloaded and its folder deleted on the calling thread.
     */
    public void retireNow(Environment environment, Location evacuation) {
        if (environment.phase() == Environment.Phase.RETIRED) {
            return;
        }
        environment.phase(Environment.Phase.RETIRING);
        World world = environment.world();
        if (Bukkit.getWorld(world.getUID()) != null) {
            world.removePluginChunkTickets(this.plugin);
            for (Player player : new ArrayList<>(world.getPlayers())) {
                if (player.isDead()) {
                    player.spigot().respawn();
                }
                player.closeInventory();
                player.teleport(evacuation, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
            if (!Bukkit.unloadWorld(world, false)) {
                this.plugin.getLogger().warning("Could not unload " + world.getName()
                        + " during shutdown; it will be removed on the next server start.");
                environment.phase(Environment.Phase.RETIRED);
                return;
            }
        }
        environment.phase(Environment.Phase.RETIRED);
        if (this.deleteRecursively(environment.folder())) {
            this.forget(environment.folder());
        }
    }

    private static void evacuate(Player player, Location target) {
        if (target == null) {
            return;
        }
        if (player.isDead()) {
            // Forces the respawn screen to close; the respawn listener routes the player to the active world.
            player.spigot().respawn();
            return;
        }
        player.closeInventory();
        player.leaveVehicle();
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    private void deleteAsync(Path folder) {
        if (!this.plugin.isEnabled()) {
            if (this.deleteRecursively(folder)) {
                this.forget(folder);
            }
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            // Region files can stay locked for a moment after unloading on Windows, so retry a few times.
            for (int attempt = 1; attempt <= 6; attempt++) {
                if (this.deleteRecursively(folder)) {
                    this.forget(folder);
                    return;
                }
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            this.plugin.getLogger().warning("Could not delete " + folder + "; it will be retried on the next start.");
        });
    }

    /** @return true if the folder no longer exists afterwards */
    private boolean deleteRecursively(Path folder) {
        if (!Files.exists(folder)) {
            return true;
        }
        if (!this.isSafeToDelete(folder)) {
            this.plugin.getLogger().severe("Refusing to delete " + folder + ": it does not look like a ChunkFun world folder.");
            this.forget(folder);
            return true;
        }
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return !Files.exists(folder);
        } catch (IOException ex) {
            this.plugin.getLogger().log(Level.FINE, "Deleting " + folder + " failed", ex);
            return false;
        }
    }

    /**
     * Guard against deleting anything that is not a ChunkFun world: the folder must be strictly inside the
     * server's world container, have a "chunkfun" path segment below it, and must not contain or equal any
     * loaded world's folder.
     */
    boolean isSafeToDelete(Path folder) {
        Path target = folder.toAbsolutePath().normalize();
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        if (!target.startsWith(container) || target.equals(container)) {
            return false;
        }
        Path relative = container.relativize(target);
        boolean marked = false;
        for (Path segment : relative) {
            if (segment.toString().toLowerCase(Locale.ROOT).contains(MARKER)) {
                marked = true;
                break;
            }
        }
        if (!marked) {
            return false;
        }
        for (World world : Bukkit.getWorlds()) {
            Path loaded = world.getWorldPath().toAbsolutePath().normalize();
            if (loaded.startsWith(target)) {
                return false;
            }
        }
        return true;
    }

    private boolean isLoadedWorldFolder(Path folder) {
        Path target = folder.toAbsolutePath().normalize();
        for (World world : Bukkit.getWorlds()) {
            if (world.getWorldPath().toAbsolutePath().normalize().equals(target)) {
                return true;
            }
        }
        return false;
    }

    private void readLedger() {
        if (!Files.exists(this.ledger)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(this.ledger, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    this.tracked.add(line.trim());
                }
            }
        } catch (IOException ex) {
            this.plugin.getLogger().log(Level.WARNING, "Could not read " + this.ledger, ex);
        }
    }

    private void writeLedger() {
        try {
            Files.createDirectories(this.ledger.getParent());
            Files.write(this.ledger, this.tracked, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            this.plugin.getLogger().log(Level.WARNING, "Could not write " + this.ledger, ex);
        }
    }
}
