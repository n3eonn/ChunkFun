package dev.chunkfun.world;

import java.util.Locale;
import java.util.Optional;

/** A slot in the rotation. The END slot resolves to one of two concrete variants when it is prepared. */
public enum EnvironmentType {
    OVERWORLD,
    NETHER,
    END,
    ANCIENT_CITY;

    public static Optional<EnvironmentType> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
