package dev.chunkfun.supply;

import net.kyori.adventure.text.format.NamedTextColor;

/** The five loot categories a lucky block can contain. */
public enum SupplyCategory {
    ARMOR("Armor", NamedTextColor.DARK_GRAY),
    TOOLS("Tools", NamedTextColor.BLUE),
    ELYTRA("Elytra", NamedTextColor.AQUA),
    FOOD("Food", NamedTextColor.GREEN),
    CRYSTALS("Crystals", NamedTextColor.DARK_PURPLE);

    private final String displayName;
    private final NamedTextColor color;

    SupplyCategory(String displayName, NamedTextColor color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String displayName() {
        return this.displayName;
    }

    public NamedTextColor color() {
        return this.color;
    }
}
