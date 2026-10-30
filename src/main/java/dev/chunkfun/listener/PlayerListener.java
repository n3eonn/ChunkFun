package dev.chunkfun.listener;

import dev.chunkfun.ChunkFunPlugin;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Routes joining and respawning players into the active environment and manages per-player UI. */
public final class PlayerListener implements Listener {

    private final ChunkFunPlugin plugin;

    public PlayerListener(ChunkFunPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Runs before the player is placed in a world. A player whose saved position is in an old (deleted)
     * environment, or anywhere outside the active one, spawns straight into the active environment.
     * Async: only touches thread-safe state.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        Location override = this.plugin.game().joinSpawnFor(event.getSpawnLocation());
        if (override != null) {
            event.setSpawnLocation(override);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.plugin.scoreboards().attach(player);
        this.plugin.specialEvents().onJoin(player);
        // Next tick: the environment may have changed while the player was in the configuration phase.
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline()) {
                this.plugin.game().handleJoin(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.plugin.scoreboards().detach(event.getPlayer());
        this.plugin.specialEvents().onQuit(event.getPlayer());
        this.plugin.game().forget(event.getPlayer());
    }

    /** Short arrival protection after a rotation teleport or respawn. The void still kills. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && event.getCause() != EntityDamageEvent.DamageCause.VOID
                && event.getCause() != EntityDamageEvent.DamageCause.KILL
                && this.plugin.game().isProtected(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        this.plugin.game().handleRespawn(event);
    }
}
