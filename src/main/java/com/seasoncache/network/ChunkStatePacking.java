package com.seasoncache.network;

public final class ChunkStatePacking {
    private static final int CHUNK_COORD_BIAS = 0x2000000; // 33,554,432; supports ±33,554,431 chunk coords

    private ChunkStatePacking() {
    }

    public static long packChunkState(int chunkX, int chunkZ, boolean snowy) {
        long x = ((long) (chunkX + CHUNK_COORD_BIAS)) & 0x3FFFFFFL;
        long z = ((long) (chunkZ + CHUNK_COORD_BIAS)) & 0x3FFFFFFL;
        long snowBit = snowy ? 1L : 0L;
        return (x << 27) | (z << 1) | snowBit;
    }

    public static int unpackChunkX(long packed) {
        return (int) (((packed >> 27) & 0x3FFFFFFL) - CHUNK_COORD_BIAS);
    }

    public static int unpackChunkZ(long packed) {
        return (int) (((packed >> 1) & 0x3FFFFFFL) - CHUNK_COORD_BIAS);
    }

    public static boolean unpackSnowy(long packed) {
        return (packed & 1L) != 0L;
    }
}
