package dev.chunkfun.events.special;

import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.NamedTextColor;

/** The four special events. */
public enum SpecialEventType {
    TNT_RAIN("TNT Rain", "Explosives are falling from the sky!", NamedTextColor.RED, BossBar.Color.RED),
    SHEEP_ADVENTURE("Sheep Adventure", "Furious rams are hunting you!", NamedTextColor.LIGHT_PURPLE, BossBar.Color.PINK),
    FLOOD("The Flood", "The water is rising!", NamedTextColor.AQUA, BossBar.Color.BLUE),
    METEOR_ATTACK("Meteor Attack", "Burning rocks are raining down!", NamedTextColor.GOLD, BossBar.Color.YELLOW),
    LIGHTNING_STORM("Lightning Storm", "The sky is striking back!", NamedTextColor.YELLOW, BossBar.Color.WHITE),
    ANVIL_RAIN("Anvil Rain", "Watch your head!", NamedTextColor.DARK_GRAY, BossBar.Color.PURPLE),
    LOW_GRAVITY("Low Gravity", "Jump higher, fall slower!", NamedTextColor.GREEN, BossBar.Color.GREEN);

    private final String displayName;
    private final String description;
    private final NamedTextColor color;
    private final BossBar.Color barColor;

    SpecialEventType(String displayName, String description, NamedTextColor color, BossBar.Color barColor) {
        this.displayName = displayName;
        this.description = description;
        this.color = color;
        this.barColor = barColor;
    }

    public String displayName() {
        return this.displayName;
    }

    public String description() {
        return this.description;
    }

    public NamedTextColor color() {
        return this.color;
    }

    public BossBar.Color barColor() {
        return this.barColor;
    }

    public static Optional<SpecialEventType> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (SpecialEventType type : values()) {
            if (type.name().equals(normalized) || type.name().replace("_", "").equals(normalized.replace("_", ""))) {
                return Optional.of(type);
            }
        }
        if (normalized.equals("METEOR") || normalized.equals("METEORS")) {
            return Optional.of(METEOR_ATTACK);
        }
        if (normalized.equals("SHEEP")) {
            return Optional.of(SHEEP_ADVENTURE);
        }
        if (normalized.equals("TNT")) {
            return Optional.of(TNT_RAIN);
        }
        if (normalized.equals("LIGHTNING")) {
            return Optional.of(LIGHTNING_STORM);
        }
        if (normalized.equals("ANVIL") || normalized.equals("ANVILS")) {
            return Optional.of(ANVIL_RAIN);
        }
        if (normalized.equals("GRAVITY")) {
            return Optional.of(LOW_GRAVITY);
        }
        return Optional.empty();
    }
}
