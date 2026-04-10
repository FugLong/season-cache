package com.seasoncache.core;

import net.minecraft.util.math.ChunkPos;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads stored MOTION_BLOCKING_NO_LEAVES heightmaps from Anvil .mca region files
 * for all present chunks without invoking the chunk generator's noise stack.
 *
 * <p>This is the primary performance fix for {@link UnloadedChunkCoverageBuilder}.
 * The previous approach called {@code ChunkGenerator.getHeightOnGround()} per chunk on
 * the server tick thread, taking 5–10 ms per call. With 100 K+ unloaded chunks that
 * produced multi-hour initial build times. Reading the stored heightmap from disk is
 * O(region-file-read + cheap-decompression) and runs on the IO thread, reducing total
 * build time from hours to tens of seconds.
 *
 * <p>Results also reflect actual player-modified terrain rather than only generator
 * terrain, which is more correct.
 *
 * <h2>Anvil format (brief)</h2>
 * <pre>
 *   Bytes 0–4095  : header – 1024 × 4-byte entries: (sectorOffset &lt;&lt; 8) | sectorCount
 *   Bytes 4096+   : chunk data at sectorOffset × 4096:
 *                     4-byte length | 1-byte compression | (length-1) bytes data
 * </pre>
 *
 * <h2>Chunk NBT structure</h2>
 * <pre>
 *   root TAG_Compound
 *     "Heightmaps" TAG_Compound
 *       "MOTION_BLOCKING_NO_LEAVES" TAG_Long_Array  (SimpleBitStorage packed)
 * </pre>
 *
 * <h2>Packed heightmap decoding (Minecraft 1.18+ SimpleBitStorage, no word-spanning)</h2>
 * <pre>
 *   bitsPerEntry  = 32 - Integer.numberOfLeadingZeros(worldHeight)   [9 for overworld]
 *   valuesPerLong = 64 / bitsPerEntry                                 [7 for 9-bit]
 *   stored[i]     = (longs[i/7] >> ((i%7)*9)) &amp; 0x1FF
 *   surfaceY      = stored[centerIndex] + bottomY - 1
 * </pre>
 *
 * <p>Runs exclusively on the IO thread. Never called from the server tick thread.
 */
public final class RegionHeightmapReader {

    /** Returned when the heightmap cannot be read for a chunk; caller should fall back to generator. */
    public static final int UNAVAILABLE = Integer.MIN_VALUE;

    /** Center of each chunk — biome and height are sampled here for coarse coverage. */
    private static final int SAMPLE_LOCAL_X = 8;
    private static final int SAMPLE_LOCAL_Z = 8;
    private static final int SAMPLE_INDEX   = SAMPLE_LOCAL_Z * 16 + SAMPLE_LOCAL_X; // 136

    private static final String TARGET_KEY = "MOTION_BLOCKING_NO_LEAVES";

    private RegionHeightmapReader() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads surface Y values for every chunk present in the given region file.
     *
     * <p>The entire file is read into memory in one syscall for sequential efficiency,
     * then each chunk is decompressed and its Heightmaps compound parsed individually.
     * The parser stops reading each chunk as soon as the target key is found, so it
     * never touches the large {@code sections} (block-data) list in practice.
     *
     * @param regionFile  path to the {@code .mca} region file
     * @param regionX     Anvil region X coordinate
     * @param regionZ     Anvil region Z coordinate
     * @param bottomY     world minimum Y (e.g. {@code -64} for the overworld)
     * @param worldHeight world total height in blocks (e.g. {@code 384} for the overworld)
     * @return list of entries, one per present chunk;
     *         {@code surfaceY == UNAVAILABLE} signals caller to fall back to the generator
     */
    public static List<ChunkSurfaceEntry> readSurfaceHeights(
            Path regionFile, int regionX, int regionZ, int bottomY, int worldHeight) {

        List<ChunkSurfaceEntry> results = new ArrayList<>();

        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(regionFile);
        } catch (Exception e) {
            return results;
        }
        if (fileBytes.length < 8192) return results;

        // SimpleBitStorage parameters — constant for all chunks in this dimension.
        int bitsPerEntry  = Math.max(1, 32 - Integer.numberOfLeadingZeros(worldHeight));
        int valuesPerLong = 64 / bitsPerEntry;
        long mask         = (1L << bitsPerEntry) - 1L;

        ByteBuffer header = ByteBuffer.wrap(fileBytes, 0, 4096).order(ByteOrder.BIG_ENDIAN);

        for (int index = 0; index < 1024; index++) {
            int location = header.getInt(index * 4);
            if (location == 0) continue; // chunk not present in this region

            int localX = index & 31;
            int localZ = index >> 5;
            ChunkPos chunkPos = new ChunkPos(regionX * 32 + localX, regionZ * 32 + localZ);

            int sectorOffset = (location >> 8) & 0x00FFFFFF;
            int surfaceY = extractSurfaceY(fileBytes, sectorOffset, bottomY, bitsPerEntry, valuesPerLong, mask);
            results.add(new ChunkSurfaceEntry(chunkPos, surfaceY));
        }

        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-chunk extraction
    // ─────────────────────────────────────────────────────────────────────────

    private static int extractSurfaceY(byte[] file, int sectorOffset,
            int bottomY, int bitsPerEntry, int valuesPerLong, long mask) {

        long byteOff = (long) sectorOffset * 4096L;
        if (byteOff + 5 > file.length) return UNAVAILABLE;

        int p      = (int) byteOff;
        int length = ((file[p]   & 0xFF) << 24)
                   | ((file[p+1] & 0xFF) << 16)
                   | ((file[p+2] & 0xFF) <<  8)
                   |  (file[p+3] & 0xFF);
        if (length <= 1) return UNAVAILABLE;

        int compression = file[p + 4] & 0xFF;
        int dataStart   = p + 5;
        int dataLen     = length - 1;
        if (dataStart + dataLen > file.length) return UNAVAILABLE;

        try {
            byte[] compressedData = new byte[dataLen];
            System.arraycopy(file, dataStart, compressedData, 0, dataLen);

            try (DataInputStream in = openDecompressed(compressedData, compression)) {
                if (in == null) return UNAVAILABLE;

                long[] heightmapLongs = findHeightmap(in);
                if (heightmapLongs == null) return UNAVAILABLE;

                int longIndex = SAMPLE_INDEX / valuesPerLong;
                int bitOffset = (SAMPLE_INDEX % valuesPerLong) * bitsPerEntry;
                if (longIndex >= heightmapLongs.length) return UNAVAILABLE;

                int storedValue = (int) ((heightmapLongs[longIndex] >> bitOffset) & mask);
                // stored = (absoluteTopY - bottomY)  →  surfaceY = absoluteTopY - 1
                return storedValue + bottomY - 1;
            }
        } catch (Exception e) {
            return UNAVAILABLE;
        }
    }

    private static DataInputStream openDecompressed(byte[] data, int compression) {
        try {
            return switch (compression) {
                case 1  -> new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)));
                case 2  -> new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)));
                case 3  -> new DataInputStream(new ByteArrayInputStream(data)); // uncompressed
                default -> null; // unsupported (e.g. LZ4, external file)
            };
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Targeted NBT parser — extracts only MOTION_BLOCKING_NO_LEAVES
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads the root TAG_Compound from the decompressed chunk stream and searches
     * for {@code Heightmaps} → {@value TARGET_KEY}. Returns as soon as the target
     * is found, so the large {@code sections} (block-data) list is never parsed when
     * Heightmaps appears earlier in the compound — which is the common case for
     * fully-generated 1.21.1 chunks.
     */
    private static long[] findHeightmap(DataInputStream in) throws IOException {
        int rootType = in.readByte() & 0xFF;
        if (rootType != 10) return null; // must be TAG_Compound
        skipString(in);                  // root compound name (empty for chunk NBT)
        return searchRootCompound(in);
    }

    private static long[] searchRootCompound(DataInputStream in) throws IOException {
        while (true) {
            int type = in.readByte() & 0xFF;
            if (type == 0) return null; // TAG_End — Heightmaps not present

            String name = readString(in);

            if (type == 10 && "Heightmaps".equals(name)) {
                return searchHeightmapsCompound(in);
            }
            skipPayload(in, type);
        }
    }

    private static long[] searchHeightmapsCompound(DataInputStream in) throws IOException {
        while (true) {
            int type = in.readByte() & 0xFF;
            if (type == 0) return null; // TAG_End — target key not present

            String name = readString(in);

            if (type == 12 && TARGET_KEY.equals(name)) {
                // Found — read the long array and return immediately.
                int count = in.readInt();
                long[] longs = new long[count];
                for (int i = 0; i < count; i++) longs[i] = in.readLong();
                return longs;
            }
            skipPayload(in, type);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NBT skip helpers — handles all tag types including recursive containers
    // ─────────────────────────────────────────────────────────────────────────

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        if (len == 0) return "";
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void skipString(DataInputStream in) throws IOException {
        in.skipNBytes(in.readUnsignedShort());
    }

    private static void skipPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1  -> in.skipNBytes(1);
            case 2  -> in.skipNBytes(2);
            case 3  -> in.skipNBytes(4);
            case 4  -> in.skipNBytes(8);
            case 5  -> in.skipNBytes(4);
            case 6  -> in.skipNBytes(8);
            case 7  -> { int n = in.readInt(); in.skipNBytes(n); }
            case 8  -> skipString(in);
            case 9  -> skipList(in);
            case 10 -> skipCompound(in);
            case 11 -> { int n = in.readInt(); in.skipNBytes((long) n * 4); }
            case 12 -> { int n = in.readInt(); in.skipNBytes((long) n * 8); }
            default -> throw new IOException("Unknown NBT tag type: " + type);
        }
    }

    private static void skipList(DataInputStream in) throws IOException {
        int elementType = in.readByte() & 0xFF;
        int count       = in.readInt();
        if (count <= 0 || elementType == 0) return;
        for (int i = 0; i < count; i++) skipPayload(in, elementType);
    }

    private static void skipCompound(DataInputStream in) throws IOException {
        while (true) {
            int type = in.readByte() & 0xFF;
            if (type == 0) return;
            skipString(in);
            skipPayload(in, type);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result type
    // ─────────────────────────────────────────────────────────────────────────

    public record ChunkSurfaceEntry(ChunkPos chunkPos, int surfaceY) {
        public boolean isAvailable() { return surfaceY != UNAVAILABLE; }
    }
}
