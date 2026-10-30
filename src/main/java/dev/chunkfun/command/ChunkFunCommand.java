package dev.chunkfun.command;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.events.special.SpecialEventType;
import dev.chunkfun.game.GameManager;
import dev.chunkfun.supply.SupplyTier;
import dev.chunkfun.util.Text;
import dev.chunkfun.util.TimeFormat;
import dev.chunkfun.world.Environment;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;

/**
 * {@code /chunkfun <start|stop|next|event|supply|mob|info|reload>} with tab completion. Each subcommand has its
 * own permission node; {@code chunkfun.admin} grants all of them.
 */
public final class ChunkFunCommand implements BasicCommand {

    private record Sub(String name, String permission, String usage, String description) {
    }

    private static final List<Sub> SUBS = List.of(
            new Sub("start", "chunkfun.command.start", "/chunkfun start", "Start the game"),
            new Sub("stop", "chunkfun.command.stop", "/chunkfun stop", "Stop the game safely"),
            new Sub("next", "chunkfun.command.next", "/chunkfun next", "Force the next environment rotation"),
            new Sub("event", "chunkfun.command.event", "/chunkfun event [type]", "Trigger a (random) special event"),
            new Sub("supply", "chunkfun.command.supply", "/chunkfun supply [tier]", "Drop lucky blocks (loot tier: legendary, rare, common)"),
            new Sub("mob", "chunkfun.command.mob", "/chunkfun mob", "Trigger a mob event"),
            new Sub("info", "chunkfun.command.info", "/chunkfun info", "Show current game information"),
            new Sub("reload", "chunkfun.command.reload", "/chunkfun reload", "Reload config.yml"));

    private final ChunkFunPlugin plugin;

    public ChunkFunCommand(ChunkFunPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            this.help(sender);
            return;
        }
        Optional<Sub> sub = SUBS.stream().filter(s -> s.name().equalsIgnoreCase(args[0])).findFirst();
        if (sub.isEmpty()) {
            sender.sendMessage(Text.error("Unknown subcommand '" + args[0] + "'."));
            this.help(sender);
            return;
        }
        if (!sender.hasPermission(sub.get().permission())) {
            sender.sendMessage(Text.error("You do not have permission to do that."));
            return;
        }
        GameManager game = this.plugin.game();
        switch (sub.get().name()) {
            case "start" -> sender.sendMessage(game.start(sender)
                    ? Text.success("Starting ChunkFun. The first environment is being generated...")
                    : Text.error("The game is already running (state: " + game.state() + ")."));
            case "stop" -> sender.sendMessage(game.stop(sender)
                    ? Text.success("ChunkFun stopped. All players were returned to the lobby.")
                    : Text.error("The game is not running."));
            case "next" -> sender.sendMessage(game.forceNext()
                    ? Text.success("Rotating to the next environment.")
                    : Text.error("Rotation is only possible while the game is running and not already changing."));
            case "event" -> this.event(sender, args);
            case "supply" -> {
                SupplyTier tier = null;
                if (args.length >= 2) {
                    tier = SupplyTier.parse(args[1]).orElse(null);
                    if (tier == null) {
                        sender.sendMessage(Text.error("Unknown tier. Use legendary, rare or common."));
                        return;
                    }
                }
                sender.sendMessage(game.triggerSupply(tier)
                        ? Text.success("Lucky blocks dropped.")
                        : Text.error("The game must be running to drop lucky blocks."));
            }
            case "mob" -> sender.sendMessage(game.triggerMobEvent()
                    ? Text.success("Mob event triggered.")
                    : Text.error("The game must be running to trigger a mob event."));
            case "info" -> this.info(sender);
            case "reload" -> {
                ChunkFunConfig config = this.plugin.reloadSettings();
                sender.sendMessage(config.warningCount() == 0
                        ? Text.success("Configuration reloaded.")
                        : Text.error("Configuration reloaded with " + config.warningCount() + " warning(s); see the console."));
            }
            default -> this.help(sender);
        }
    }

    private void event(CommandSender sender, String[] args) {
        GameManager game = this.plugin.game();
        Environment active = game.activeEnvironment();
        if (game.state() != GameManager.State.RUNNING || active == null) {
            sender.sendMessage(Text.error("Special events can only start while the game is running."));
            return;
        }
        if (this.plugin.specialEvents().isActive()) {
            sender.sendMessage(Text.error("A special event is already running."));
            return;
        }
        boolean started;
        if (args.length >= 2) {
            Optional<SpecialEventType> type = SpecialEventType.parse(args[1]);
            if (type.isEmpty()) {
                sender.sendMessage(Text.error("Unknown event. Use one of: " + this.eventNames()));
                return;
            }
            started = this.plugin.specialEvents().start(type.get(), active);
        } else {
            started = this.plugin.specialEvents().startRandom(active);
        }
        sender.sendMessage(started ? Text.success("Special event started.") : Text.error("The special event could not start."));
    }

    private void info(CommandSender sender) {
        GameManager game = this.plugin.game();
        Environment active = game.activeEnvironment();
        sender.sendMessage(Text.prefixed(Component.text("Game state: ", NamedTextColor.GRAY)
                .append(Component.text(game.state().name(), NamedTextColor.YELLOW))));
        if (active != null && game.isRunning()) {
            sender.sendMessage(Component.text("  Environment: ", NamedTextColor.GRAY)
                    .append(Component.text(active.variant().displayName(), active.variant().color()))
                    .append(Component.text(" - " + active.description(), NamedTextColor.DARK_GRAY)));
            sender.sendMessage(Component.text("  World: " + active.world().getName() + "  area: "
                    + active.area().minX() + "," + active.area().minZ() + " to " + active.area().maxX() + ","
                    + active.area().maxZ(), NamedTextColor.DARK_GRAY));
            sender.sendMessage(Component.text("  Next chunk: " + TimeFormat.clock(game.secondsUntilRotation())
                    + (game.upcomingEnvironmentName() == null ? "" : " (" + game.upcomingEnvironmentName()
                    + (game.nextEnvironment() != null ? ", ready" : game.isPreparing() ? ", generating" : "") + ")"), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  Next mob event: " + TimeFormat.clock(game.secondsUntilMobEvent())
                    + "  next special event: " + TimeFormat.clock(game.secondsUntilSpecialEvent())
                    + "  next supply: " + TimeFormat.clock(game.secondsUntilSupply()), NamedTextColor.GRAY));
            SpecialEventType event = this.plugin.specialEvents().activeType();
            if (event != null) {
                sender.sendMessage(Component.text("  Active event: ", NamedTextColor.GRAY)
                        .append(Component.text(event.displayName() + " (" + this.plugin.specialEvents().remainingSeconds()
                                + "s left)", event.color())));
            }
        }
        ChunkFunConfig config = this.plugin.settings();
        StringBuilder mobs = new StringBuilder();
        for (EntityType type : config.mobEvents().mobs().weights().keySet()) {
            if (!mobs.isEmpty()) {
                mobs.append(", ");
            }
            mobs.append(type.name().toLowerCase(Locale.ROOT)).append(' ')
                    .append(String.format(Locale.ROOT, "%.0f%%", config.mobEvents().mobs().percentOf(type)));
        }
        sender.sendMessage(Component.text("  Mob table: " + mobs, NamedTextColor.DARK_GRAY));
        sender.sendMessage(Component.text("  Players online: " + Bukkit.getOnlinePlayers().size(), NamedTextColor.DARK_GRAY));
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Text.prefixed(Component.text("Commands:", NamedTextColor.GOLD)));
        for (Sub sub : SUBS) {
            if (sender.hasPermission(sub.permission())) {
                sender.sendMessage(Component.text("  " + sub.usage(), NamedTextColor.YELLOW)
                        .append(Component.text(" - " + sub.description(), NamedTextColor.GRAY)));
            }
        }
    }

    private String eventNames() {
        List<String> names = new ArrayList<>();
        for (SpecialEventType type : SpecialEventType.values()) {
            names.add(type.name().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", names);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        List<String> suggestions = new ArrayList<>();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            for (Sub sub : SUBS) {
                if (sub.name().startsWith(prefix) && sender.hasPermission(sub.permission())) {
                    suggestions.add(sub.name());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("event") && sender.hasPermission("chunkfun.command.event")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (SpecialEventType type : SpecialEventType.values()) {
                String name = type.name().toLowerCase(Locale.ROOT);
                if (name.startsWith(prefix)) {
                    suggestions.add(name);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("supply") && sender.hasPermission("chunkfun.command.supply")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (SupplyTier tier : SupplyTier.values()) {
                String name = tier.name().toLowerCase(Locale.ROOT);
                if (name.startsWith(prefix)) {
                    suggestions.add(name);
                }
            }
        }
        return suggestions;
    }
}
