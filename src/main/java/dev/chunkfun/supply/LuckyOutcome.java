package dev.chunkfun.supply;

import java.util.Locale;
import java.util.Optional;

/** What breaking a lucky block does, chosen by the weights in {@code supply.outcomes}. */
public enum LuckyOutcome {
    /** Loot from a random category and tier. */
    GOOD,
    /** Something harmless and fun: XP, a firework show, a buff, a cake party. */
    NEUTRAL,
    /** A trap: TNT, mobs, lightning, an anvil, levitation or blindness. */
    BAD;

    public static Optional<LuckyOutcome> parse(String raw) {
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Optional.empty();
        }
    }
}
