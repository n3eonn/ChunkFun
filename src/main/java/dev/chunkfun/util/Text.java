package dev.chunkfun.util;

import java.time.Duration;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;

/** Small Adventure helpers so every system formats messages the same way. */
public final class Text {

    public static final Component PREFIX = Component.text()
            .append(Component.text("[", NamedTextColor.DARK_GRAY))
            .append(Component.text("ChunkFun", NamedTextColor.GOLD, TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
            .build();

    private Text() {
    }

    public static Component prefixed(Component message) {
        return PREFIX.append(message);
    }

    public static Component info(String message) {
        return prefixed(Component.text(message, NamedTextColor.GRAY));
    }

    public static Component success(String message) {
        return prefixed(Component.text(message, NamedTextColor.GREEN));
    }

    public static Component error(String message) {
        return prefixed(Component.text(message, NamedTextColor.RED));
    }

    public static Component colored(String message, TextColor color) {
        return Component.text(message, color);
    }

    public static Component bold(String message, TextColor color) {
        return Component.text(message, color, TextDecoration.BOLD);
    }

    public static Title title(Component title, Component subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        return Title.title(title, subtitle, Title.Times.times(
                Duration.ofMillis(fadeInTicks * 50L),
                Duration.ofMillis(stayTicks * 50L),
                Duration.ofMillis(fadeOutTicks * 50L)));
    }

    public static Sound sound(String key, float volume, float pitch) {
        return Sound.sound(Key.key(key), Sound.Source.MASTER, volume, pitch);
    }

    /** Broadcasts a chat message to every online player and the console. */
    public static void broadcast(Component message) {
        Bukkit.getServer().sendMessage(message);
    }

    public static Audience everyone() {
        return Audience.audience(Bukkit.getOnlinePlayers());
    }
}
