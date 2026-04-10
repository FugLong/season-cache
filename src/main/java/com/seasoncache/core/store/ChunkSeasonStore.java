package com.seasoncache.core.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.seasoncache.SeasonCacheMod;
import com.seasoncache.core.RuntimeTypes;
import com.seasoncache.core.io.RegionIOThread;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stores per-region season clearing state and per-chunk climate decision caches.
 *
 * Data model:
 *   - Each region tracks which chunks have been cleared this epoch (sparse set).
 *   - Each chunk also caches the per-column snow/ice decisions computed by the
 *     reconciler on its first pass of a new epoch (shouldSnowBits / shouldIceBits).
 *     Subsequent reconcile passes within the same epoch use these cached bits
 *     directly without querying Serene Seasons again.
 *   - If every known chunk in a region has been cleared, the region is promoted to
 *     "fully clean" via regionEpoch, and future chunk loads skip the entire region.
 *   - All per-chunk data (cleared set + climate cache) is tied to dataEpoch and is
 *     discarded when the epoch changes.
 *
 * Threading model:
 *   All public methods are called from the server tick thread under synchronized(this).
 *   Disk IO is delegated to RegionIOThread — the tick thread never blocks on disk.
 *
 *   On a region cache miss, an empty placeholder is installed immediately and a load
 *   task is submitted to RegionIOThread. The placeholder means the chunk is treated
 *   as "not clean" and enqueued for reconciliation. When the load completes the
 *   placeholder is replaced with real data. At worst a chunk is reconciled once with
 *   a cold cache (correct, if slightly redundant).
 *
 *   Write tasks snapshot data at submission time (under the store monitor on the tick
 *   thread) so the IO thread never needs to acquire the store monitor during the
 *   actual disk write. A per-path pending-write set prevents redundant writes: if a
 *   write task is already queued for a region, new changes just leave dirty=true and
 *   the post-write callback re-submits with the latest snapshot.
 *
 * On-disk format: per-region JSON sidecar (one file per region per dimension).
 * Schema version 5 — includes persistent static chunk climate samples in addition to exact climate bits and coarse unloaded snow coverage.
 */
public final class ChunkSeasonStore {
    public static final int CURRENT_SCHEMA_VERSION = 5;
    public static final int CLIMATE_BITS_WORDS = 4; // 4 longs = 256 bits = one bit per column

    private static final Gson GSON = new GsonBuilder().create();

    private final Map<RegionKey, RegionData> loadedRegions = new HashMap<>();

    // Tracks regions with a write task already in the IO queue.
    // Protected by synchronized(this). Used to prevent redundant writes.
    private final Set<Path> pendingWritePaths = new HashSet<>();

    private RegionIOThread ioThread;

    public void setIOThread(RegionIOThread ioThread) {
        this.ioThread = ioThread;
    }

    // -------------------------------------------------------------------------
    // Public API — clearing state
    // -------------------------------------------------------------------------

    /**
     * Returns true if the chunk has already been cleared during the current epoch,
     * or if its entire region has been marked fully clean.
     *
     * Always submits region loads at HIGH priority — the caller is actively evaluating
     * this chunk and wants the sidecar data as soon as possible.
     *
     * Even on a promoted region we verify chunk membership to handle newly generated
     * chunks that appear after promotion.
     */
    public synchronized boolean isChunkClean(ServerWorld world, ChunkPos chunkPos, int currentEpoch) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);

        if (region.regionEpoch == currentEpoch) {
            if (region.clearedChunks.contains(chunkPos.toLong())) {
                return true;
            }
            // New chunk in a promoted region — revoke promotion.
            region.regionEpoch = 0;
            region.knownChunkCount++;
            region.dirty = true;
            return false;
        }

        if (region.dataEpoch != currentEpoch) return false;
        return region.clearedChunks.contains(chunkPos.toLong());
    }

    /**
     * Records that the given chunk has been cleared this epoch.
     * Promotes the region to fully clean if all known chunks are now covered.
     *
     * clearedChunks is intentionally NOT cleared on promotion — it must stay
     * populated so isChunkClean can verify individual membership when new chunks
     * appear in a promoted region.
     */
    public synchronized void markChunkCleared(ServerWorld world, ChunkPos chunkPos, int currentEpoch) {
        RegionData region = getOrSubmitLoad(world, chunkPos, false);
        ensureEpoch(region, currentEpoch);
        region.clearedChunks.add(chunkPos.toLong());
        region.dirty = true;

        if (region.knownChunkCount > 0 && region.clearedChunks.size() >= region.knownChunkCount) {
            region.regionEpoch = currentEpoch;
        }

        submitWriteIfDirty(region);
    }

    /**
     * Records the number of chunks that exist in this region (from the Anvil header).
     * Used to determine when a region can be promoted to fully clean.
     */
    public synchronized void setKnownChunkCount(ServerWorld world, int regionX, int regionZ, int count) {
        RegionData region = getOrSubmitLoad(world, new ChunkPos(regionX * 32, regionZ * 32), false);
        if (region.knownChunkCount != count) {
            region.knownChunkCount = count;
            region.dirty = true;
            submitWriteIfDirty(region);
        }
    }

    // -------------------------------------------------------------------------
    // Public API — per-chunk climate cache
    // -------------------------------------------------------------------------

    /**
     * Returns the cached per-column climate decision bits for the given chunk, or
     * null if no valid cache exists for the current epoch.
     *
     * @return long[2][4] where [0]=shouldSnowBits, [1]=shouldIceBits, or null
     */
    public synchronized long[][] getClimateBits(ServerWorld world, ChunkPos chunkPos, int currentEpoch) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        if (region.dataEpoch != currentEpoch) return null;
        return region.climateBits.get(chunkPos.toLong());
    }

    /**
     * Stores per-column climate decision bits for the given chunk.
     * Called by the reconciler after computing fresh SS decisions on a cold-cache chunk.
     */
    public synchronized void setClimateBits(
            ServerWorld world, ChunkPos chunkPos, int currentEpoch,
            long[] snowBits, long[] iceBits
    ) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        ensureEpoch(region, currentEpoch);
        region.climateBits.put(chunkPos.toLong(), new long[][]{snowBits, iceBits});
        region.dirty = true;
        submitWriteIfDirty(region);
    }


    /**
     * Stores the persistent static chunk climate sample used to derive coarse
     * unloaded snow coverage quickly on future epoch changes. This data is not tied
     * to a specific season epoch.
     */
    public synchronized void setStaticClimateSample(
            ServerWorld world, ChunkPos chunkPos, String biomeId, int surfaceY
    ) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        RuntimeTypes.StaticChunkClimate current = region.staticClimateSamples.get(chunkPos.toLong());
        RuntimeTypes.StaticChunkClimate next = new RuntimeTypes.StaticChunkClimate(biomeId, surfaceY);
        if (Objects.equals(current, next)) return;
        region.staticClimateSamples.put(chunkPos.toLong(), next);
        region.dirty = true;
        submitWriteIfDirty(region);
    }

    /**
     * Returns the persistent static chunk climate sample for this chunk, or null if
     * the chunk has not yet been analysed. Static climate samples survive epoch
     * changes.
     */
    public synchronized RuntimeTypes.StaticChunkClimate getStaticClimateSample(ServerWorld world, ChunkPos chunkPos) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        return region.staticClimateSamples.get(chunkPos.toLong());
    }

    /**
     * Returns true when a persistent static chunk climate sample exists for the chunk.
     */
    public synchronized boolean hasStaticClimateSample(ServerWorld world, ChunkPos chunkPos) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        return region.staticClimateSamples.containsKey(chunkPos.toLong());
    }





    /**
     * Stores coarse chunk-level coverage flags for an unloaded chunk. These are used
     * as approximate authoritative shader inputs until an exact loaded-chunk reconcile
     * produces full per-column climate bits for the same epoch.
     */
    public synchronized void setCoverageState(
            ServerWorld world, ChunkPos chunkPos, int currentEpoch,
            boolean snowy
    ) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        ensureEpoch(region, currentEpoch);
        boolean[] current = region.coverageStates.get(chunkPos.toLong());
        if (current != null && current.length > 0 && current[0] == snowy) return;
        region.coverageStates.put(chunkPos.toLong(), new boolean[]{snowy});
        region.dirty = true;
        submitWriteIfDirty(region);
    }

    /**
     * Sweeps all in-memory regions and collects every chunk with a snowy=true
     * coverage state for the given epoch into the provided set.
     *
     * Called at season transition before flushAll() runs so the full picture of
     * winter snowy chunks is captured into a plain set that lives independently
     * of the store — immune to epoch eviction and coverage re-derive overwrites.
     */
    /**
     * Sweeps all coverage data — both in-memory regions and sidecar files on disk —
     * and collects every chunk with a snowy=true coverage state for the given epoch.
     *
     * Called at season transition before flushAll() runs. In-memory regions are read
     * directly. Disk sidecars that haven't been loaded into memory yet (typically
     * distant regions outside render distance) are read from disk directly so the
     * complete picture of snowy chunks is captured, not just what happened to be
     * resident in memory during the session.
     *
     * Runs synchronously on the server tick thread — acceptable cost for a one-shot
     * transition operation.
     */
    public synchronized void collectSnowyChunks(ServerWorld world, int epoch, Set<ChunkPos> out) {
        String dimensionId = world.getRegistryKey().getValue().toString();

        // Pass 1: in-memory regions — fast, no IO.
        for (Map.Entry<RegionKey, RegionData> entry : this.loadedRegions.entrySet()) {
            if (!entry.getKey().dimensionId.equals(dimensionId)) continue;
            RegionData region = entry.getValue();
            if (region.dataEpoch != epoch) continue;
            for (Map.Entry<Long, boolean[]> coverage : region.coverageStates.entrySet()) {
                if (coverage.getValue() != null
                        && coverage.getValue().length > 0
                        && coverage.getValue()[0]) {
                    out.add(new ChunkPos(coverage.getKey()));
                }
            }
        }

        // Pass 2: sidecar files on disk that aren't loaded in memory yet.
        // These are the distant regions outside render distance that were never
        // requested this session but hold authoritative winter coverage data.
        String dimPath = world.getRegistryKey().getValue().getPath();
        Path sidecarDir = world.getServer().getSavePath(WorldSavePath.ROOT)
                .resolve("seasoncache")
                .resolve(dimPath);

        if (!Files.isDirectory(sidecarDir)) return;

        // Build a set of paths already covered by in-memory regions to avoid
        // double-counting chunks whose region is in memory and also on disk.
        Set<Path> inMemoryPaths = new HashSet<>();
        for (RegionKey key : this.loadedRegions.keySet()) {
            if (!key.dimensionId.equals(dimensionId)) continue;
            inMemoryPaths.add(sidecarPath(world, key.regionX, key.regionZ));
        }

        List<Path> diskFiles = new ArrayList<>();
        try (var stream = Files.list(sidecarDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                  .filter(p -> !inMemoryPaths.contains(p))
                  .forEach(diskFiles::add);
        } catch (IOException e) {
            SeasonCacheMod.LOGGER.warn(
                    "Season Cache: failed to enumerate sidecars for snowy chunk sweep.", e);
            return;
        }

        for (Path path : diskFiles) {
            try (Reader reader = Files.newBufferedReader(path)) {
                RegionDataDisk disk = GSON.fromJson(reader, RegionDataDisk.class);
                if (disk == null || disk.dataEpoch != epoch) continue;
                if (disk.coverageStates == null) continue;
                for (CoverageEntryDisk entry : disk.coverageStates) {
                    if (entry != null && entry.snow) {
                        out.add(new ChunkPos(entry.chunkKey));
                    }
                }
            } catch (Exception e) {
                // Skip unreadable sidecars — missing chunks will be caught by
                // the normal reconcile path when they eventually load.
                SeasonCacheMod.LOGGER.debug(
                        "Season Cache: skipping unreadable sidecar {} during snowy sweep: {}",
                        path.getFileName(), e.getMessage());
            }
        }
    }


    /**
     * Returns the coarse chunk-level snow boolean for the current epoch, or null if no
     * unloaded coverage cache exists for the chunk.
     */
    public synchronized Boolean getCoverageSnowState(ServerWorld world, ChunkPos chunkPos, int currentEpoch) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        if (region.dataEpoch != currentEpoch) return null;

        boolean[] state = region.coverageStates.get(chunkPos.toLong());
        if (state == null || state.length == 0) return null;
        return state[0];
    }

    /**
     * Returns true when exact per-column climate bits are cached for the chunk in the
     * current epoch.
     */
    public synchronized boolean hasExactClimateBits(ServerWorld world, ChunkPos chunkPos, int currentEpoch) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        return region.dataEpoch == currentEpoch && region.climateBits.containsKey(chunkPos.toLong());
    }

    /**
     * Returns the best currently available authoritative chunk snow boolean:
     * exact reconcile data when present, otherwise unloaded coverage data.
     */
    public synchronized Boolean getAuthoritativeChunkSnowState(ServerWorld world, ChunkPos chunkPos, int currentEpoch) {
        Boolean exact = getChunkSnowState(world, chunkPos, currentEpoch);
        if (exact != null) return exact;
        return getCoverageSnowState(world, chunkPos, currentEpoch);
    }

    /**
     * Returns a snapshot of all authoritative chunk snow states currently held in
     * memory for the requested dimension and epoch. Exact climate bits override
     * coarse unloaded coverage when both exist for the same chunk.
     */
    public synchronized List<AuthoritativeChunkState> snapshotAuthoritativeChunkSnowStates(ServerWorld world, int currentEpoch) {
        List<AuthoritativeChunkState> states = new ArrayList<>();
        String dimensionId = world.getRegistryKey().getValue().toString();

        for (Map.Entry<RegionKey, RegionData> entry : this.loadedRegions.entrySet()) {
            if (!Objects.equals(entry.getKey().dimensionId, dimensionId)) continue;

            RegionData region = entry.getValue();
            if (region.dataEpoch != currentEpoch) continue;

            Map<Long, Boolean> merged = new HashMap<>();
            for (Map.Entry<Long, boolean[]> coverageEntry : region.coverageStates.entrySet()) {
                boolean[] coverage = coverageEntry.getValue();
                if (coverage != null && coverage.length > 0) {
                    merged.put(coverageEntry.getKey(), coverage[0]);
                }
            }
            for (Map.Entry<Long, long[][]> climateEntry : region.climateBits.entrySet()) {
                long[][] bits = climateEntry.getValue();
                if (bits == null || bits.length == 0 || bits[0] == null) continue;
                merged.put(climateEntry.getKey(), hasAnySetBit(bits[0]));
            }

            for (Map.Entry<Long, Boolean> mergedEntry : merged.entrySet()) {
                states.add(new AuthoritativeChunkState(new ChunkPos(mergedEntry.getKey()), mergedEntry.getValue()));
            }
        }

        return states;
    }

    /**
     * Returns the authoritative chunk snow boolean for the current epoch, or null if
     * the chunk has not yet been reconciled/cached for that epoch.
     *
     * A chunk is considered snowy when any bit in its cached shouldSnow mask is set.
     */
    public synchronized Boolean getChunkSnowState(ServerWorld world, ChunkPos chunkPos, int currentEpoch) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        if (region.dataEpoch != currentEpoch) return null;

        long[][] bits = region.climateBits.get(chunkPos.toLong());
        if (bits == null || bits.length == 0 || bits[0] == null) return null;

        return hasAnySetBit(bits[0]);
    }

    /**
     * Ensures the sidecar for the given region is loaded into memory.
     *
     * Called during server start for regions whose cached epoch already matches the
     * current season — their coverage and climate data is correct on disk and only
     * needs to be in memory before the first player snapshot is built. No reconciliation
     * or coverage recomputation is performed; this is purely a background load trigger.
     *
     * Safe to call from the IO thread (acquires the store monitor internally).
     */
    public synchronized void preWarmRegion(ServerWorld world, int regionX, int regionZ) {
        getOrSubmitLoad(world, new ChunkPos(regionX * 32, regionZ * 32), false);
    }

    // -------------------------------------------------------------------------
    // Public API — admin / invalidation
    // -------------------------------------------------------------------------

    /**
     * Performs a global invalidate.
     *
     * In-memory state is cleared immediately on the tick thread.
     * On-disk sidecar zeroing is submitted as a LOW priority background task.
     * Progress is tracked via RegionIOThread.invalidationProgress().
     */
    public synchronized void invalidateAll(MinecraftServer server) {
        for (RegionData region : this.loadedRegions.values()) {
            region.clearedChunks.clear();
            region.climateBits.clear();
            region.coverageStates.clear();
            region.staticClimateSamples.clear();
            region.dataEpoch = 0;
            region.regionEpoch = 0;
            region.dirty = true;
        }

        // Submit writes for zeroed in-memory regions.
        for (RegionData region : this.loadedRegions.values()) {
            submitWriteIfDirty(region);
        }

        // Collect in-memory paths to skip in the disk walk.
        Set<Path> inMemoryPaths = new HashSet<>();
        for (RegionData region : this.loadedRegions.values()) {
            inMemoryPaths.add(region.path);
        }

        Path sidecarRoot = server.getSavePath(WorldSavePath.ROOT).resolve("seasoncache");
        if (!Files.isDirectory(sidecarRoot)) return;

        List<Path> diskFiles = new ArrayList<>();
        try (var stream = Files.walk(sidecarRoot)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                  .filter(p -> !inMemoryPaths.contains(p))
                  .forEach(diskFiles::add);
        } catch (IOException e) {
            SeasonCacheMod.LOGGER.warn(
                "Season Cache: failed to enumerate sidecar files for invalidation walk.", e);
            return;
        }

        this.ioThread.submitInvalidationWalk(diskFiles.size(), () -> {
            for (Path path : diskFiles) {
                zeroEpochsOnDisk(path);
                this.ioThread.incrementInvalidationProgress();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Public API — status counters
    // -------------------------------------------------------------------------

    public synchronized int clearedChunkCount(int currentEpoch) {
        int count = 0;
        for (RegionData region : this.loadedRegions.values()) {
            if (region.regionEpoch == currentEpoch) {
                count += region.knownChunkCount > 0 ? region.knownChunkCount : region.clearedChunks.size();
            } else if (region.dataEpoch == currentEpoch) {
                count += region.clearedChunks.size();
            }
        }
        return count;
    }

    public synchronized int cachedClimateChunkCount(int currentEpoch) {
        int count = 0;
        for (RegionData region : this.loadedRegions.values()) {
            if (region.dataEpoch == currentEpoch) count += region.climateBits.size();
        }
        return count;
    }

    public synchronized int cachedCoverageChunkCount(int currentEpoch) {
        int count = 0;
        for (RegionData region : this.loadedRegions.values()) {
            if (region.dataEpoch == currentEpoch) count += region.coverageStates.size();
        }
        return count;
    }

    public synchronized int cachedStaticClimateChunkCount() {
        int count = 0;
        for (RegionData region : this.loadedRegions.values()) {
            count += region.staticClimateSamples.size();
        }
        return count;
    }

    public synchronized int fullyCleanRegionCount(int currentEpoch) {
        int count = 0;
        for (RegionData region : this.loadedRegions.values()) {
            if (region.regionEpoch == currentEpoch) count++;
        }
        return count;
    }

    public synchronized int dirtyRegionCount() {
        int count = 0;
        for (RegionData region : this.loadedRegions.values()) {
            if (region.dirty) count++;
        }
        return count;
    }

    /**
     * Submits write tasks for all in-memory regions regardless of dirty state.
     * Called on season change (async write) and server shutdown (write then drain).
     */
    public synchronized void flushAll() {
        for (RegionData region : this.loadedRegions.values()) {
            region.dirty = true; // force write even if already considered clean
            submitWriteIfDirty(region);
        }
    }

    /**
     * Submits write tasks for dirty in-memory regions.
     * Called when the precache builder completes its scan.
     */
    public synchronized void flushDirty() {
        for (RegionData region : this.loadedRegions.values()) {
            submitWriteIfDirty(region);
        }
    }

    // -------------------------------------------------------------------------
    // Internal — region access
    // -------------------------------------------------------------------------

    /**
     * Returns the in-memory RegionData for the given chunk's region.
     *
     * Cache hit: returns immediately.
     * Cache miss: installs an empty placeholder, submits a background load task,
     *   returns the placeholder. When the load completes, the placeholder is
     *   replaced under the store's monitor.
     *
     * @param isHighPriority if true, submits the load at HIGH priority (active
     *                       reconciliation is waiting). Otherwise MEDIUM or LOW
     *                       depending on player neighbourhood.
     */
    private RegionData getOrSubmitLoad(ServerWorld world, ChunkPos chunkPos, boolean isHighPriority) {
        int regionX = Math.floorDiv(chunkPos.x, 32);
        int regionZ = Math.floorDiv(chunkPos.z, 32);
        String dimensionId = world.getRegistryKey().getValue().toString();
        RegionKey key = new RegionKey(dimensionId, regionX, regionZ);

        RegionData cached = this.loadedRegions.get(key);
        if (cached != null) return cached;

        Path path = sidecarPath(world, regionX, regionZ);
        RegionData placeholder = new RegionData(path);
        this.loadedRegions.put(key, placeholder);

        String regionKey = dimensionId + ":" + regionX + ":" + regionZ;
        this.ioThread.submitLoad(regionKey, isHighPriority, () -> {
            RegionData loaded = readRegionFromDisk(path);
            synchronized (ChunkSeasonStore.this) {
                RegionData current = this.loadedRegions.get(key);
                if (current == null || current == placeholder) {
                    if (current == null) {
                        this.loadedRegions.put(key, loaded);
                    } else {
                        mergeLoadedIntoExisting(current, loaded);
                        this.loadedRegions.put(key, current);
                    }
                } else {
                    mergeLoadedIntoExisting(current, loaded);
                }
            }
        });

        return placeholder;
    }


    /**
     * Merges freshly loaded sidecar data into an existing in-memory region placeholder
     * or mutated region. Existing in-memory epoch data and static climate samples win
     * over older on-disk values so background region loads cannot silently discard
     * updates produced while the load was in flight.
     */
    private static void mergeLoadedIntoExisting(RegionData existing, RegionData loaded) {
        if (existing.knownChunkCount == 0) {
            existing.knownChunkCount = loaded.knownChunkCount;
        } else if (loaded.knownChunkCount > 0) {
            existing.knownChunkCount = Math.max(existing.knownChunkCount, loaded.knownChunkCount);
        }

        if (existing.dataEpoch == 0) {
            existing.dataEpoch = loaded.dataEpoch;
            existing.regionEpoch = loaded.regionEpoch;
            existing.clearedChunks.addAll(loaded.clearedChunks);
            existing.climateBits.putAll(loaded.climateBits);
            existing.coverageStates.putAll(loaded.coverageStates);
        } else if (existing.dataEpoch == loaded.dataEpoch) {
            loaded.clearedChunks.forEach(existing.clearedChunks::add);
            loaded.climateBits.forEach(existing.climateBits::putIfAbsent);
            loaded.coverageStates.forEach(existing.coverageStates::putIfAbsent);
            if (existing.regionEpoch == 0) {
                existing.regionEpoch = loaded.regionEpoch;
            }
        }

        loaded.staticClimateSamples.forEach(existing.staticClimateSamples::putIfAbsent);
    }

    private RegionData readRegionFromDisk(Path path) {
        RegionData region = new RegionData(path);
        if (!Files.exists(path)) return region;

        try (Reader reader = Files.newBufferedReader(path)) {
            RegionDataDisk disk = GSON.fromJson(reader, RegionDataDisk.class);
            if (disk != null) {
                if (disk.schemaVersion != CURRENT_SCHEMA_VERSION) {
                    SeasonCacheMod.LOGGER.warn(
                        "Season Cache: sidecar {} has schema version {} (expected {}), " +
                        "discarding incompatible data. Region will rebuild from scratch.",
                        path.getFileName(), disk.schemaVersion, CURRENT_SCHEMA_VERSION);
                } else {
                    region.dataEpoch = disk.dataEpoch;
                    region.regionEpoch = disk.regionEpoch;
                    region.knownChunkCount = disk.knownChunkCount;
                    if (disk.clearedChunks != null) {
                        for (long k : disk.clearedChunks) region.clearedChunks.add(k);
                    }
                    if (disk.climateBits != null) {
                        for (ClimateEntryDisk entry : disk.climateBits) {
                            if (entry != null && entry.snowBits != null && entry.iceBits != null
                                    && entry.snowBits.length == CLIMATE_BITS_WORDS
                                    && entry.iceBits.length == CLIMATE_BITS_WORDS) {
                                region.climateBits.put(entry.chunkKey,
                                        new long[][]{entry.snowBits, entry.iceBits});
                            }
                        }
                    }
                    if (disk.coverageStates != null) {
                        for (CoverageEntryDisk entry : disk.coverageStates) {
                            if (entry != null) {
                                region.coverageStates.put(entry.chunkKey, new boolean[]{entry.snow});
                            }
                        }
                    }
                    if (disk.staticClimateSamples != null) {
                        for (StaticClimateEntryDisk entry : disk.staticClimateSamples) {
                            if (entry != null && entry.biomeId != null && !entry.biomeId.isBlank()) {
                                region.staticClimateSamples.put(entry.chunkKey,
                                        new RuntimeTypes.StaticChunkClimate(entry.biomeId, entry.surfaceY));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            SeasonCacheMod.LOGGER.warn(
                "Season Cache: failed to read sidecar {}, starting fresh for this region.",
                path.getFileName(), e);
        }
        return region;
    }

    // -------------------------------------------------------------------------
    // Internal — write path
    // -------------------------------------------------------------------------

    /**
     * Submits a write task for the given region if it is dirty and no write task
     * is already pending for it.
     *
     * If a write task is already queued (pendingWritePaths contains region.path),
     * the dirty flag is left true. The post-write callback checks this flag and
     * re-submits if needed, ensuring no update is silently lost.
     *
     * The data snapshot is taken here, under the store monitor (tick thread).
     * The IO thread receives an immutable snapshot and never acquires the store
     * monitor during the actual disk write.
     */
    private void submitWriteIfDirty(RegionData region) {
        if (!region.dirty) return;
        if (this.pendingWritePaths.contains(region.path)) {
            // Write already queued. Leave dirty=true — post-write callback will re-submit.
            return;
        }

        RegionDataDisk snapshot = snapshotToDisk(region);
        region.dirty = false;
        this.pendingWritePaths.add(region.path);

        this.ioThread.submitWrite(() -> {
            writeSnapshot(snapshot, region.path);
            synchronized (ChunkSeasonStore.this) {
                this.pendingWritePaths.remove(region.path);
                // New changes may have arrived while the write was executing.
                if (region.dirty) {
                    submitWriteIfDirty(region);
                }
            }
        });
    }

    /**
     * Snapshots a RegionData into a serialisable disk object.
     * Must be called under the store monitor (tick thread).
     */
    private static RegionDataDisk snapshotToDisk(RegionData region) {
        RegionDataDisk disk = new RegionDataDisk();
        disk.dataEpoch = region.dataEpoch;
        disk.regionEpoch = region.regionEpoch;
        disk.knownChunkCount = region.knownChunkCount;
        disk.clearedChunks = region.clearedChunks.stream().mapToLong(Long::longValue).toArray();
        disk.climateBits = region.climateBits.entrySet().stream()
                .map(e -> {
                    ClimateEntryDisk entry = new ClimateEntryDisk();
                    entry.chunkKey = e.getKey();
                    entry.snowBits = e.getValue()[0];
                    entry.iceBits  = e.getValue()[1];
                    return entry;
                })
                .toArray(ClimateEntryDisk[]::new);
        disk.coverageStates = region.coverageStates.entrySet().stream()
                .map(e -> {
                    CoverageEntryDisk entry = new CoverageEntryDisk();
                    entry.chunkKey = e.getKey();
                    entry.snow = e.getValue()[0];
                    return entry;
                })
                .toArray(CoverageEntryDisk[]::new);
        disk.staticClimateSamples = region.staticClimateSamples.entrySet().stream()
                .map(e -> {
                    StaticClimateEntryDisk entry = new StaticClimateEntryDisk();
                    entry.chunkKey = e.getKey();
                    entry.biomeId = e.getValue().biomeId();
                    entry.surfaceY = e.getValue().surfaceY();
                    return entry;
                })
                .toArray(StaticClimateEntryDisk[]::new);
        return disk;
    }

    /**
     * Writes a pre-built disk snapshot to the given path.
     * Runs entirely on the IO thread. Never acquires the store monitor.
     */
    private static void writeSnapshot(RegionDataDisk snapshot, Path path) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(snapshot, writer);
            }
        } catch (Exception e) {
            SeasonCacheMod.LOGGER.error(
                "Season Cache: failed to write sidecar {}. Data may be lost on restart.",
                path.getFileName(), e);
        }
    }


    private static boolean hasAnySetBit(long[] words) {
        for (long word : words) {
            if (word != 0L) return true;
        }
        return false;
    }

    private static void zeroEpochsOnDisk(Path path) {
        RegionDataDisk disk = null;
        try (Reader reader = Files.newBufferedReader(path)) {
            disk = GSON.fromJson(reader, RegionDataDisk.class);
        } catch (Exception e) {
            SeasonCacheMod.LOGGER.warn(
                "Season Cache: failed to read sidecar {} during invalidation, skipping.",
                path.getFileName(), e);
            return;
        }
        if (disk == null) return;

        disk.dataEpoch = 0;
        disk.regionEpoch = 0;
        disk.clearedChunks = new long[0];
        disk.climateBits = new ClimateEntryDisk[0];
        disk.coverageStates = new CoverageEntryDisk[0];
        disk.staticClimateSamples = new StaticClimateEntryDisk[0];
        // knownChunkCount preserved intentionally.

        writeSnapshot(disk, path);
    }

    private static void ensureEpoch(RegionData region, int currentEpoch) {
        if (region.dataEpoch != currentEpoch) {
            region.clearedChunks.clear();
            region.climateBits.clear();
            region.coverageStates.clear();
            region.dataEpoch = currentEpoch;
        }
    }

    private static Path sidecarPath(ServerWorld world, int regionX, int regionZ) {
        String dimPath = world.getRegistryKey().getValue().getPath();
        return world.getServer().getSavePath(WorldSavePath.ROOT)
                .resolve("seasoncache")
                .resolve(dimPath)
                .resolve("r." + regionX + "." + regionZ + ".json");
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    private static final class RegionKey {
        private final String dimensionId;
        private final int regionX;
        private final int regionZ;

        private RegionKey(String dimensionId, int regionX, int regionZ) {
            this.dimensionId = dimensionId;
            this.regionX = regionX;
            this.regionZ = regionZ;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof RegionKey other)) return false;
            return this.regionX == other.regionX
                && this.regionZ == other.regionZ
                && Objects.equals(this.dimensionId, other.dimensionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.dimensionId, this.regionX, this.regionZ);
        }
    }

    private static final class RegionData {
        private final Path path;
        private int dataEpoch = 0;
        private int regionEpoch = 0;
        private int knownChunkCount = 0;
        private final Set<Long> clearedChunks = new HashSet<>();
        // chunkKey → [snowBits(4 longs), iceBits(4 longs)]
        private final Map<Long, long[][]> climateBits = new HashMap<>();
        // chunkKey → [snowy] coarse unloaded snow coverage for the current epoch
        private final Map<Long, boolean[]> coverageStates = new HashMap<>();
        // chunkKey → persistent static chunk climate sample used for fast epoch derivation
        private final Map<Long, RuntimeTypes.StaticChunkClimate> staticClimateSamples = new HashMap<>();
        private boolean dirty = false;

        private RegionData(Path path) {
            this.path = path;
        }
    }

    private static final class RegionDataDisk {
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        int dataEpoch = 0;
        int regionEpoch = 0;
        int knownChunkCount = 0;
        long[] clearedChunks = new long[0];
        ClimateEntryDisk[] climateBits = new ClimateEntryDisk[0];
        CoverageEntryDisk[] coverageStates = new CoverageEntryDisk[0];
        StaticClimateEntryDisk[] staticClimateSamples = new StaticClimateEntryDisk[0];
    }

    public record AuthoritativeChunkState(ChunkPos chunkPos, boolean snowy) {
    }

    private static final class ClimateEntryDisk {
        long chunkKey;
        long[] snowBits;
        long[] iceBits;
    }

    private static final class CoverageEntryDisk {
        long chunkKey;
        boolean snow;
    }

    private static final class StaticClimateEntryDisk {
        long chunkKey;
        String biomeId;
        int surfaceY;
    }
}
