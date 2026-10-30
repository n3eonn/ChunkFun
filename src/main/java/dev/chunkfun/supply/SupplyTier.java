package dev.chunkfun.supply;

import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Quality of a supply category within one drop. LEGENDARY is the full netherite loot; RARE and COMMON are the
 * weaker diamond and iron versions.
 */
public enum SupplyTier {
    LEGENDARY("Legendary", NamedTextColor.GOLD),
    RARE("Rare", NamedTextColor.AQUA),
    COMMON("Common", NamedTextColor.GRAY);

    private final String displayName;
    private final NamedTextColor color;

    SupplyTier(String displayName, NamedTextColor color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String displayName() {
        return this.displayName;
    }

    public NamedTextColor color() {
        return this.color;
    }

    public static Optional<SupplyTier> parse(String raw) {
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Optional.empty();
        }
    }
}
