package dev.chunkfun.world;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.World;

/** The concrete kind of world that gets generated for a rotation slot. */
public enum EnvironmentVariant {
    OVERWORLD(EnvironmentType.OVERWORLD, World.Environment.NORMAL, "overworld", "OVERWORLD", NamedTextColor.GREEN, Material.STONE),
    NETHER(EnvironmentType.NETHER, World.Environment.NETHER, "nether", "NETHER", NamedTextColor.RED, Material.NETHERRACK),
    END_CITY(EnvironmentType.END, World.Environment.THE_END, "end", "END", NamedTextColor.LIGHT_PURPLE, Material.END_STONE),
    END_ISLAND(EnvironmentType.END, World.Environment.THE_END, "end_island", "END ISLAND", NamedTextColor.DARK_PURPLE, Material.END_STONE),
    ANCIENT_CITY(EnvironmentType.ANCIENT_CITY, World.Environment.NORMAL, "ancient_city", "ANCIENT CITY", NamedTextColor.DARK_AQUA, Material.DEEPSLATE);

    private final EnvironmentType type;
    private final World.Environment bukkitEnvironment;
    private final String id;
    private final String displayName;
    private final NamedTextColor color;
    private final Material fallbackFloor;

    EnvironmentVariant(EnvironmentType type, World.Environment bukkitEnvironment, String id, String displayName,
                       NamedTextColor color, Material fallbackFloor) {
        this.type = type;
        this.bukkitEnvironment = bukkitEnvironment;
        this.id = id;
        this.displayName = displayName;
        this.color = color;
        this.fallbackFloor = fallbackFloor;
    }

    public EnvironmentType type() {
        return this.type;
    }

    public World.Environment bukkitEnvironment() {
        return this.bukkitEnvironment;
    }

    /** Lower-case identifier used in world keys. */
    public String id() {
        return this.id;
    }

    public String displayName() {
        return this.displayName;
    }

    public NamedTextColor color() {
        return this.color;
    }

    /** Block used if an emergency spawn platform has to be built. */
    public Material fallbackFloor() {
        return this.fallbackFloor;
    }
}
