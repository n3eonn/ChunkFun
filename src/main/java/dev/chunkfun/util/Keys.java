package dev.chunkfun.util;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Persistent data keys used to tag entities that ChunkFun owns and must clean up. */
public final class Keys {

    private static NamespacedKey managed;

    private Keys() {
    }

    public static void init(Plugin plugin) {
        managed = new NamespacedKey(plugin, "managed");
    }

    /** Marks an entity as created by ChunkFun with a short purpose tag (e.g. "event_sheep"). */
    public static void markManaged(Entity entity, String purpose) {
        entity.getPersistentDataContainer().set(managed, PersistentDataType.STRING, purpose);
    }

    public static boolean isManaged(Entity entity) {
        return entity.getPersistentDataContainer().has(managed, PersistentDataType.STRING);
    }
}
