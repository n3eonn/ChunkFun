package dev.chunkfun.util;

/**
 * Packs block positions into a single long (26 bits X, 26 bits Z, 12 bits Y), the same layout vanilla uses
 * for {@code BlockPos.asLong}. Covers X/Z of +-33 million and Y of -2048..2047.
 */
public final class BlockKeys {

    private BlockKeys() {
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    public static int x(long key) {
        return (int) (key >> 38);
    }

    public static int y(long key) {
        return (int) (key << 52 >> 52);
    }

    public static int z(long key) {
        return (int) (key << 26 >> 38);
    }
}
