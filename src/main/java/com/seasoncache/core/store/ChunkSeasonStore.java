package com.seasoncache.core.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.seasoncache.SeasonCacheMod;
import com.seasoncache.core.RuntimeTypes;
import com.seasoncache.core.io.RegionIOThread;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.ChunkPos;

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
 * Persistent chunk-rule sidecar store.
 *
 * Authoritative state is intentionally minimal:
 *   - staticClimateSamples (persistent inputs)
 *   - chunkSeasonRules   (persistent truth)
 *   - clearedChunks      (per-epoch applied state only)
 *
 * Legacy coverage/climate fields remain only as empty compatibility fields on disk so
 * older sidecars can be read and then rewritten into the simplified schema.
 */
public final class ChunkSeasonStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final int CURRENT_SCHEMA_VERSION = 7;

    private RegionIOThread ioThread;
    private final Map<RegionKey, RegionData> loadedRegions = new HashMap<>();
    private final Set<Path> pendingWritePaths = new HashSet<>();

    public void setIOThread(RegionIOThread ioThread) {
        this.ioThread = ioThread;
    }

    public synchronized boolean isChunkClean(ServerLevel world, ChunkPos chunkPos, int currentEpoch) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        return region.dataEpoch == currentEpoch && region.clearedChunks.contains(chunkPos.pack());
    }

    public synchronized void markChunkCleared(ServerLevel world, ChunkPos chunkPos, int currentEpoch) {
        RegionData region = getOrSubmitLoad(world, chunkPos, false);
        ensureEpoch(region, currentEpoch);
        if (region.clearedChunks.add(chunkPos.pack())) {
            region.dirty = true;
            submitWriteIfDirty(region);
        }
    }

    public synchronized void unmarkChunkCleared(ServerLevel world, ChunkPos chunkPos, int currentEpoch) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        if (region.dataEpoch == currentEpoch && region.clearedChunks.remove(chunkPos.pack())) {
            region.dirty = true;
            submitWriteIfDirty(region);
        }
    }

    /**
     * Returns true if this chunk has already received its baseline sweep pass for the
     * given epoch. When true, SS is considered the authority on block state for this
     * chunk until the epoch changes — the on-load sweep will not fire again.
     */
    public synchronized boolean isChunkSwept(ServerLevel world, ChunkPos chunkPos, int currentEpoch) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        Integer swept = region.sweepEpochs.get(chunkPos.pack());
        return swept != null && swept == currentEpoch;
    }

    /**
     * Records that this chunk has received its baseline sweep pass for the given epoch.
     * Persisted to disk so server restarts within the same season do not re-sweep chunks
     * that SS has already had time to naturally adjust.
     */
    public synchronized void markChunkSwept(ServerLevel world, ChunkPos chunkPos, int currentEpoch) {
        RegionData region = getOrSubmitLoad(world, chunkPos, false);
        long key = chunkPos.pack();
        // Clear the pending unmark — reconcile has now confirmed the correct state.
        region.pendingSweepUnmarks.remove(key);
        Integer existing = region.sweepEpochs.get(key);
        if (existing == null || existing != currentEpoch) {
            region.sweepEpochs.put(key, currentEpoch);
            region.dirty = true;
            submitWriteIfDirty(region);
        }
    }

    /**
     * Clears the sweep record for this chunk, forcing a fresh baseline pass next time
     * it loads. Used by /seasoncache sweep to recover from incorrect reconciliation.
     */
    public synchronized void unmarkChunkSwept(ServerLevel world, ChunkPos chunkPos) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        long key = chunkPos.pack();
        region.sweepEpochs.remove(key);
        // Track the unmark intent so mergeLoadedIntoExisting doesn't restore the
        // disk-persisted sweep record if the async IO load completes after this call.
        region.pendingSweepUnmarks.add(key);
        region.dirty = true;
        submitWriteIfDirty(region);
    }

    public synchronized void setKnownChunkCount(ServerLevel world, int regionX, int regionZ, int count) {
        RegionData region = getOrSubmitLoad(world, new ChunkPos(regionX * 32, regionZ * 32), false);
        if (region.knownChunkCount != count) {
            region.knownChunkCount = count;
            region.dirty = true;
            submitWriteIfDirty(region);
        }
    }

    public synchronized void setStaticClimateSample(ServerLevel world, ChunkPos chunkPos, String biomeId, int surfaceY) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        RuntimeTypes.StaticChunkClimate current = region.staticClimateSamples.get(chunkPos.pack());
        RuntimeTypes.StaticChunkClimate next = new RuntimeTypes.StaticChunkClimate(biomeId, surfaceY);
        if (Objects.equals(current, next)) return;
        region.staticClimateSamples.put(chunkPos.pack(), next);
        region.dirty = true;
        submitWriteIfDirty(region);
    }

    public synchronized RuntimeTypes.StaticChunkClimate getStaticClimateSample(ServerLevel world, ChunkPos chunkPos) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        return region.staticClimateSamples.get(chunkPos.pack());
    }

    public synchronized boolean hasStaticClimateSample(ServerLevel world, ChunkPos chunkPos) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        return region.staticClimateSamples.containsKey(chunkPos.pack());
    }

    public synchronized void setChunkSeasonRule(ServerLevel world, ChunkPos chunkPos, RuntimeTypes.ChunkSeasonRule rule) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        RuntimeTypes.ChunkSeasonRule current = region.chunkSeasonRules.get(chunkPos.pack());
        if (Objects.equals(current, rule)) return;
        region.chunkSeasonRules.put(chunkPos.pack(), rule);
        region.dirty = true;
        submitWriteIfDirty(region);
    }

    public synchronized RuntimeTypes.ChunkSeasonRule getChunkSeasonRule(ServerLevel world, ChunkPos chunkPos) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        return region.chunkSeasonRules.get(chunkPos.pack());
    }

    public synchronized boolean hasChunkSeasonRule(ServerLevel world, ChunkPos chunkPos) {
        RegionData region = getOrSubmitLoad(world, chunkPos, true);
        return region.chunkSeasonRules.containsKey(chunkPos.pack());
    }

    public synchronized void collectSnowyChunks(ServerLevel world, int epoch, Set<ChunkPos> out) {
        int seasonIndex = SeasonCacheMod.get().seasonRuleConfig()
                .seasonIndex(SeasonCacheMod.get().seasonProvider().snapshot(world).seasonKey());
        String dimensionId = world.dimension().identifier().toString();
        for (Map.Entry<RegionKey, RegionData> entry : this.loadedRegions.entrySet()) {
            if (!Objects.equals(entry.getKey().dimensionId, dimensionId)) continue;
            for (Map.Entry<Long, RuntimeTypes.ChunkSeasonRule> ruleEntry : entry.getValue().chunkSeasonRules.entrySet()) {
                if (ruleEntry.getValue().isSnowyInSeason(seasonIndex)) {
                    out.add(ChunkPos.unpack(ruleEntry.getKey()));
                }
            }
        }
    }

    public synchronized Boolean getCoverageSnowState(ServerLevel world, ChunkPos chunkPos, int currentEpoch) {
        RuntimeTypes.ChunkSeasonRule rule = getChunkSeasonRule(world, chunkPos);
        if (rule == null) return null;
        int seasonIndex = SeasonCacheMod.get().seasonRuleConfig()
                .seasonIndex(SeasonCacheMod.get().seasonProvider().snapshot(world).seasonKey());
        return rule.isSnowyInSeason(seasonIndex);
    }

    public synchronized Boolean getAuthoritativeChunkSnowState(ServerLevel world, ChunkPos chunkPos, int currentEpoch) {
        RuntimeTypes.ChunkSeasonRule rule = getChunkSeasonRule(world, chunkPos);
        if (rule == null) return null;
        int seasonIndex = SeasonCacheMod.get().seasonRuleConfig()
                .seasonIndex(SeasonCacheMod.get().seasonProvider().snapshot(world).seasonKey());
        return rule.isSnowyInSeason(seasonIndex);
    }

    public synchronized List<AuthoritativeChunkState> snapshotAuthoritativeChunkSnowStates(ServerLevel world, int currentEpoch) {
        List<AuthoritativeChunkState> states = new ArrayList<>();
        int seasonIndex = SeasonCacheMod.get().seasonRuleConfig()
                .seasonIndex(SeasonCacheMod.get().seasonProvider().snapshot(world).seasonKey());
        String dimensionId = world.dimension().identifier().toString();

        for (Map.Entry<RegionKey, RegionData> entry : this.loadedRegions.entrySet()) {
            if (!Objects.equals(entry.getKey().dimensionId, dimensionId)) continue;
            for (Map.Entry<Long, RuntimeTypes.ChunkSeasonRule> ruleEntry : entry.getValue().chunkSeasonRules.entrySet()) {
                states.add(new AuthoritativeChunkState(
                        ChunkPos.unpack(ruleEntry.getKey()),
                        ruleEntry.getValue().isSnowyInSeason(seasonIndex)
                ));
            }
        }
        return states;
    }

    public synchronized Boolean getChunkSnowState(ServerLevel world, ChunkPos chunkPos, int currentEpoch) {
        return getAuthoritativeChunkSnowState(world, chunkPos, currentEpoch);
    }

    public synchronized void preWarmRegion(ServerLevel world, int regionX, int regionZ) {
        getOrSubmitLoad(world, new ChunkPos(regionX * 32, regionZ * 32), false);
    }

    public synchronized void invalidateAll(MinecraftServer server) {
        for (RegionData region : this.loadedRegions.values()) {
            region.dataEpoch = 0;
            region.clearedChunks.clear();
            region.staticClimateSamples.clear();
            region.chunkSeasonRules.clear();
            region.dirty = true;
        }
        for (RegionData region : this.loadedRegions.values()) {
            submitWriteIfDirty(region);
        }

        Set<Path> inMemoryPaths = new HashSet<>();
        for (RegionData region : this.loadedRegions.values()) {
            inMemoryPaths.add(region.path);
        }

        Path sidecarRoot = server.getWorldPath(LevelResource.ROOT).resolve("seasoncache");
        if (!Files.isDirectory(sidecarRoot)) return;

        List<Path> diskFiles = new ArrayList<>();
        try (var stream = Files.walk(sidecarRoot)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !inMemoryPaths.contains(p))
                    .forEach(diskFiles::add);
        } catch (IOException e) {
            SeasonCacheMod.LOGGER.warn("Season Cache: failed to enumerate sidecar files for invalidation walk.", e);
            return;
        }

        this.ioThread.submitInvalidationWalk(diskFiles.size(), () -> {
            for (Path path : diskFiles) {
                zeroEpochsOnDisk(path);
                this.ioThread.incrementInvalidationProgress();
            }
        });
    }

    public synchronized void invalidateDynamicStateKeepStatic(MinecraftServer server, boolean clearRules) {
        for (RegionData region : this.loadedRegions.values()) {
            region.dataEpoch = 0;
            region.clearedChunks.clear();
            if (clearRules) {
                region.chunkSeasonRules.clear();
            }
            region.dirty = true;
        }
        for (RegionData region : this.loadedRegions.values()) {
            submitWriteIfDirty(region);
        }

        Set<Path> inMemoryPaths = new HashSet<>();
        for (RegionData region : this.loadedRegions.values()) {
            inMemoryPaths.add(region.path);
        }

        Path sidecarRoot = server.getWorldPath(LevelResource.ROOT).resolve("seasoncache");
        if (!Files.isDirectory(sidecarRoot)) return;

        List<Path> diskFiles = new ArrayList<>();
        try (var stream = Files.walk(sidecarRoot)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !inMemoryPaths.contains(p))
                    .forEach(diskFiles::add);
        } catch (IOException e) {
            SeasonCacheMod.LOGGER.warn("Season Cache: failed to enumerate sidecar files for rule invalidation walk.", e);
            return;
        }

        this.ioThread.submitInvalidationWalk(diskFiles.size(), () -> {
            for (Path path : diskFiles) {
                zeroDynamicStateOnDisk(path, clearRules);
                this.ioThread.incrementInvalidationProgress();
            }
        });
    }

    public synchronized int clearedChunkCount(int currentEpoch) {
        int count = 0;
        for (RegionData region : this.loadedRegions.values()) {
            if (region.dataEpoch == currentEpoch) {
                count += region.clearedChunks.size();
            }
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

    public synchronized int dirtyRegionCount() {
        int count = 0;
        for (RegionData region : this.loadedRegions.values()) {
            if (region.dirty) count++;
        }
        return count;
    }

    public synchronized void flushAll() {
        for (RegionData region : this.loadedRegions.values()) {
            region.dirty = true;
            submitWriteIfDirty(region);
        }
    }

    public synchronized void flushDirty() {
        for (RegionData region : this.loadedRegions.values()) {
            submitWriteIfDirty(region);
        }
    }

    private RegionData getOrSubmitLoad(ServerLevel world, ChunkPos chunkPos, boolean isHighPriority) {
        int regionX = Math.floorDiv(chunkPos.x(), 32);
        int regionZ = Math.floorDiv(chunkPos.z(), 32);
        String dimensionId = world.dimension().identifier().toString();
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
                if (current == null) {
                    this.loadedRegions.put(key, loaded);
                } else {
                    mergeLoadedIntoExisting(current, loaded);
                }
            }
        });

        return placeholder;
    }

    private static void mergeLoadedIntoExisting(RegionData existing, RegionData loaded) {
        if (existing.knownChunkCount == 0) {
            existing.knownChunkCount = loaded.knownChunkCount;
        } else if (loaded.knownChunkCount > 0) {
            existing.knownChunkCount = Math.max(existing.knownChunkCount, loaded.knownChunkCount);
        }

        // Only merge clearedChunks when epochs agree. If existing has already been
        // advanced to a new epoch by ensureEpoch, don't let stale disk data pollute it.
        if (existing.dataEpoch == 0) {
            existing.dataEpoch = loaded.dataEpoch;
            existing.clearedChunks.addAll(loaded.clearedChunks);
        } else if (existing.dataEpoch == loaded.dataEpoch) {
            existing.clearedChunks.addAll(loaded.clearedChunks);
        }
        // If epochs differ, existing was already advanced past what's on disk — discard
        // the stale clearedChunks from the loaded data entirely.

        loaded.staticClimateSamples.forEach(existing.staticClimateSamples::putIfAbsent);
        loaded.chunkSeasonRules.forEach(existing.chunkSeasonRules::putIfAbsent);

        // Respect explicit unmark intents — don't restore sweep records for chunks
        // where unmarkChunkSwept was called since the region was loaded.
        loaded.sweepEpochs.forEach((key, epoch) -> {
            if (!existing.pendingSweepUnmarks.contains(key)) {
                existing.sweepEpochs.putIfAbsent(key, epoch);
            }
        });
    }

    private RegionData readRegionFromDisk(Path path) {
        RegionData region = new RegionData(path);
        if (!Files.exists(path)) return region;

        try (Reader reader = Files.newBufferedReader(path)) {
            RegionDataDisk disk = GSON.fromJson(reader, RegionDataDisk.class);
            if (disk == null) return region;

            region.knownChunkCount = disk.knownChunkCount;

            if (disk.staticClimateSamples != null) {
                for (StaticClimateEntryDisk entry : disk.staticClimateSamples) {
                    if (entry != null && entry.biomeId != null && !entry.biomeId.isBlank()) {
                        region.staticClimateSamples.put(entry.chunkKey,
                                new RuntimeTypes.StaticChunkClimate(entry.biomeId, entry.surfaceY));
                    }
                }
            }

            if (disk.schemaVersion == CURRENT_SCHEMA_VERSION) {
                if (disk.chunkSeasonRules != null) {
                    for (RuleEntryDisk entry : disk.chunkSeasonRules) {
                        if (entry != null) {
                            region.chunkSeasonRules.put(entry.chunkKey,
                                    new RuntimeTypes.ChunkSeasonRule(entry.snowEpochMask, entry.perennialNoTouch));
                        }
                    }
                }

                region.dataEpoch = disk.dataEpoch;
                if (disk.clearedChunks != null) {
                    for (long key : disk.clearedChunks) {
                        region.clearedChunks.add(key);
                    }
                }
                if (disk.sweepEpochs != null) {
                    for (SweepEntryDisk entry : disk.sweepEpochs) {
                        if (entry != null) {
                            region.sweepEpochs.put(entry.chunkKey, entry.epoch);
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

    private void submitWriteIfDirty(RegionData region) {
        if (!region.dirty) return;
        if (this.pendingWritePaths.contains(region.path)) return;

        RegionDataDisk snapshot = snapshotToDisk(region);
        region.dirty = false;
        this.pendingWritePaths.add(region.path);

        this.ioThread.submitWrite(() -> {
            writeSnapshot(snapshot, region.path);
            synchronized (ChunkSeasonStore.this) {
                this.pendingWritePaths.remove(region.path);
                if (region.dirty) {
                    submitWriteIfDirty(region);
                }
            }
        });
    }

    private static RegionDataDisk snapshotToDisk(RegionData region) {
        RegionDataDisk disk = new RegionDataDisk();
        disk.dataEpoch = region.dataEpoch;
        disk.knownChunkCount = region.knownChunkCount;
        disk.clearedChunks = region.clearedChunks.stream().mapToLong(Long::longValue).toArray();
        disk.staticClimateSamples = region.staticClimateSamples.entrySet().stream()
                .map(e -> {
                    StaticClimateEntryDisk entry = new StaticClimateEntryDisk();
                    entry.chunkKey = e.getKey();
                    entry.biomeId = e.getValue().biomeId();
                    entry.surfaceY = e.getValue().surfaceY();
                    return entry;
                })
                .toArray(StaticClimateEntryDisk[]::new);
        disk.chunkSeasonRules = region.chunkSeasonRules.entrySet().stream()
                .map(e -> {
                    RuleEntryDisk entry = new RuleEntryDisk();
                    entry.chunkKey = e.getKey();
                    entry.snowEpochMask = e.getValue().snowEpochMask();
                    entry.perennialNoTouch = e.getValue().perennialNoTouch();
                    return entry;
                })
                .toArray(RuleEntryDisk[]::new);
        disk.sweepEpochs = region.sweepEpochs.entrySet().stream()
                .filter(e -> e.getValue() == region.dataEpoch)
                .map(e -> {
                    SweepEntryDisk entry = new SweepEntryDisk();
                    entry.chunkKey = e.getKey();
                    entry.epoch = e.getValue();
                    return entry;
                })
                .toArray(SweepEntryDisk[]::new);
        return disk;
    }

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

    private static void zeroEpochsOnDisk(Path path) {
        RegionDataDisk disk = readRegionDataDisk(path);
        if (disk == null) return;
        disk.dataEpoch = 0;
        disk.regionEpoch = 0;
        disk.clearedChunks = new long[0];
        disk.staticClimateSamples = new StaticClimateEntryDisk[0];
        disk.chunkSeasonRules = new RuleEntryDisk[0];
        writeSnapshot(disk, path);
    }

    private static void zeroDynamicStateOnDisk(Path path, boolean clearRules) {
        RegionDataDisk disk = readRegionDataDisk(path);
        if (disk == null) return;
        disk.dataEpoch = 0;
        disk.regionEpoch = 0;
        disk.clearedChunks = new long[0];
        if (clearRules) {
            disk.chunkSeasonRules = new RuleEntryDisk[0];
        }
        writeSnapshot(disk, path);
    }

    private static RegionDataDisk readRegionDataDisk(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            return GSON.fromJson(reader, RegionDataDisk.class);
        } catch (Exception e) {
            SeasonCacheMod.LOGGER.warn(
                    "Season Cache: failed to read sidecar {} during invalidation, skipping.",
                    path.getFileName(), e);
            return null;
        }
    }

    private static void ensureEpoch(RegionData region, int currentEpoch) {
        if (region.dataEpoch != currentEpoch) {
            region.dataEpoch = currentEpoch;
            region.clearedChunks.clear();
        }
    }

    private static Path sidecarPath(ServerLevel world, int regionX, int regionZ) {
        String dimPath = world.dimension().identifier().getPath();
        return world.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("seasoncache")
                .resolve(dimPath)
                .resolve("r." + regionX + "." + regionZ + ".json");
    }

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
        private int knownChunkCount = 0;
        private final Set<Long> clearedChunks = new HashSet<>();
        private final Map<Long, RuntimeTypes.StaticChunkClimate> staticClimateSamples = new HashMap<>();
        private final Map<Long, RuntimeTypes.ChunkSeasonRule> chunkSeasonRules = new HashMap<>();
        private final Map<Long, Integer> sweepEpochs = new HashMap<>();
        /**
         * Chunk keys where unmarkChunkSwept was called but the async IO load has not
         * yet completed. Prevents mergeLoadedIntoExisting from restoring stale sweep
         * records from disk that were explicitly cleared this session.
         */
        private final Set<Long> pendingSweepUnmarks = new HashSet<>();
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
        StaticClimateEntryDisk[] staticClimateSamples = new StaticClimateEntryDisk[0];
        RuleEntryDisk[] chunkSeasonRules = new RuleEntryDisk[0];
        SweepEntryDisk[] sweepEpochs = new SweepEntryDisk[0];
    }

    public record AuthoritativeChunkState(ChunkPos chunkPos, boolean snowy) {
    }

    private static final class StaticClimateEntryDisk {
        long chunkKey;
        String biomeId;
        int surfaceY;
    }

    private static final class RuleEntryDisk {
        long chunkKey;
        int snowEpochMask;
        boolean perennialNoTouch;
    }

    private static final class SweepEntryDisk {
        long chunkKey;
        int epoch;
    }
}
