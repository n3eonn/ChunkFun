package dev.chunkfun.util;

/** Formatting helpers for countdowns and playtime. */
public final class TimeFormat {

    private TimeFormat() {
    }

    /** Formats seconds as {@code MM:SS}, or {@code H:MM:SS} once an hour is reached. */
    public static String clock(long totalSeconds) {
        long seconds = Math.max(0L, totalSeconds);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        if (hours > 0L) {
            return String.format("%d:%02d:%02d", hours, minutes, secs);
        }
        return String.format("%02d:%02d", minutes, secs);
    }

    /** Formats seconds as {@code HH:MM:SS} (hours are not capped at 24). */
    public static String playtime(long totalSeconds) {
        long seconds = Math.max(0L, totalSeconds);
        return String.format("%02d:%02d:%02d", seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L);
    }
}
