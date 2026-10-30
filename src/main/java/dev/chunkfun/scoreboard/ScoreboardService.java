package dev.chunkfun.scoreboard;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.events.special.SpecialEventType;
import dev.chunkfun.game.GameManager;
import dev.chunkfun.util.TimeFormat;
import dev.chunkfun.world.Environment;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Per-player sidebar. Each player gets one scoreboard and one objective, created once. Lines are fixed
 * score entries whose visible text is the score's custom name, so an update only changes the text of
 * lines that actually changed: there is no flicker and no objective is ever re-created. One repeating task
 * updates every board.
 */
public final class ScoreboardService {

    private static final int LINES = 11;
    private static final TextColor LABEL = NamedTextColor.GRAY;
    private static final TextColor VALUE = NamedTextColor.WHITE;

    private static final class Board {
        final Scoreboard scoreboard;
        final Objective objective;
        final Score[] scores = new Score[LINES];
        final Component[] last = new Component[LINES];
        Team glowTeam;

        Board(Scoreboard scoreboard, Objective objective) {
            this.scoreboard = scoreboard;
            this.objective = objective;
        }
    }

    private final ChunkFunPlugin plugin;
    private final Map<UUID, Board> boards = new HashMap<>();
    private BukkitTask task;

    public ScoreboardService(ChunkFunPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        ChunkFunConfig.ScoreboardSettings settings = this.plugin.settings().scoreboard();
        if (!settings.enabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.attach(player);
        }
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::updateAll, 1L, settings.updateIntervalTicks());
    }

    /** Applies a reloaded configuration (interval, title, enabled flag). */
    public void restart() {
        this.shutdown();
        this.start();
    }

    public void shutdown() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Map.Entry<UUID, Board> entry : this.boards.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.getScoreboard() == entry.getValue().scoreboard) {
                player.setScoreboard(main);
            }
        }
        this.boards.clear();
    }

    public void attach(Player player) {
        ChunkFunConfig.ScoreboardSettings settings = this.plugin.settings().scoreboard();
        if (!settings.enabled() || this.boards.containsKey(player.getUniqueId())) {
            return;
        }
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("chunkfun", Criteria.DUMMY, settings.title());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
        Board board = new Board(scoreboard, objective);
        // Colours the glowing outline (and name tags) of every player on this player's screen.
        board.glowTeam = scoreboard.registerNewTeam("cf_glow");
        board.glowTeam.color(this.plugin.settings().effects().glowColor());
        for (int i = 0; i < LINES; i++) {
            Score score = objective.getScore("cf_line_" + i);
            score.setScore(LINES - i);
            score.customName(Component.space());
            board.scores[i] = score;
        }
        this.boards.put(player.getUniqueId(), board);
        player.setScoreboard(scoreboard);
        this.update(player, board, this.sharedLines());
    }

    public void detach(Player player) {
        this.boards.remove(player.getUniqueId());
    }

    public void refreshAll() {
        this.updateAll();
    }

    private void updateAll() {
        if (this.boards.isEmpty()) {
            return;
        }
        Component[] shared = this.sharedLines();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Board board = this.boards.get(player.getUniqueId());
            if (board != null) {
                this.update(player, board, shared);
            }
        }
    }

    private void update(Player player, Board board, Component[] shared) {
        if (player.getScoreboard() != board.scoreboard) {
            // Another plugin replaced the scoreboard; respect that instead of fighting over it.
            return;
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!board.glowTeam.hasEntry(other.getName())) {
                board.glowTeam.addEntry(other.getName());
            }
        }
        boolean show = this.plugin.game().isRunning() || this.plugin.settings().scoreboard().showWhenStopped();
        if (!show) {
            if (board.objective.getDisplaySlot() != null) {
                board.scoreboard.clearSlot(DisplaySlot.SIDEBAR);
            }
            return;
        }
        if (board.objective.getDisplaySlot() != DisplaySlot.SIDEBAR) {
            board.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        long playtimeSeconds = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
        Component[] lines = shared.clone();
        lines[1] = line("Player: ", Component.text(player.getName(), NamedTextColor.YELLOW));
        lines[5] = line("Playtime: ", Component.text(TimeFormat.playtime(playtimeSeconds), VALUE));
        for (int i = 0; i < LINES; i++) {
            if (!Objects.equals(board.last[i], lines[i])) {
                board.scores[i].customName(lines[i]);
                board.last[i] = lines[i];
            }
        }
    }

    /** Lines that are identical for every player, computed once per update. */
    private Component[] sharedLines() {
        GameManager game = this.plugin.game();
        Component[] lines = new Component[LINES];
        Environment environment = game.activeEnvironment();
        boolean running = game.isRunning() && environment != null;

        lines[0] = Component.space();
        lines[1] = Component.space();
        if (running) {
            lines[2] = line("Environment: ", Component.text(environment.variant().displayName(), environment.variant().color()));
            lines[3] = Component.text("  " + environment.description(), NamedTextColor.DARK_GRAY);
        } else {
            String status = game.state() == GameManager.State.STARTING ? "Preparing..." : "Waiting";
            lines[2] = line("Environment: ", Component.text(status, NamedTextColor.YELLOW));
            lines[3] = Component.text("  /chunkfun start", NamedTextColor.DARK_GRAY);
        }
        lines[4] = Component.text("  ");
        lines[5] = Component.space();
        lines[6] = Component.text("   ");
        if (running) {
            lines[7] = line("Next Mob Event: ", Component.text(TimeFormat.clock(game.secondsUntilMobEvent()), NamedTextColor.RED));
            SpecialEventType activeEvent = this.plugin.specialEvents().activeType();
            if (activeEvent != null) {
                lines[8] = line("Special Event: ", Component.text(activeEvent.displayName() + " "
                        + TimeFormat.clock(this.plugin.specialEvents().remainingSeconds()), activeEvent.color()));
            } else {
                lines[8] = line("Next Special Event: ", Component.text(TimeFormat.clock(game.secondsUntilSpecialEvent()), NamedTextColor.GOLD));
            }
            Component chunk;
            if (game.state() == GameManager.State.TRANSITION && game.secondsUntilRotation() == 0) {
                chunk = Component.text("preparing...", NamedTextColor.YELLOW);
            } else {
                chunk = Component.text(TimeFormat.clock(game.secondsUntilRotation()),
                        game.state() == GameManager.State.TRANSITION ? NamedTextColor.RED : NamedTextColor.GREEN);
            }
            lines[9] = line("Next Chunk: ", chunk);
        } else {
            lines[7] = line("Next Mob Event: ", Component.text("--:--", NamedTextColor.DARK_GRAY));
            lines[8] = line("Next Special Event: ", Component.text("--:--", NamedTextColor.DARK_GRAY));
            lines[9] = line("Next Chunk: ", Component.text("--:--", NamedTextColor.DARK_GRAY));
        }
        lines[10] = Component.text("    ");
        return lines;
    }

    private static Component line(String label, Component value) {
        return Component.text(label, LABEL).append(value);
    }
}
