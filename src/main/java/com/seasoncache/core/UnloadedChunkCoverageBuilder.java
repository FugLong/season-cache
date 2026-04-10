package com.seasoncache.core;

import com.google.gson.Gson;
import com.seasoncache.SeasonCacheMod;
import com.seasoncache.config.SeasonCacheConfig;
import com.seasoncache.core.io.RegionIOThread;
import com.seasoncache.core.store.ChunkSeasonStore;
import com.seasoncache.integration.SeasonProvider;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives coarse unloaded-chunk seasonal snow coverage from per-chunk static climate
 * samples (biomeId + surfaceY) stored in Season Cache sidecars.
 *
 * Each region is categorised into one of three paths on every {@link #start} call:
 *
 *  PRE-WARM (same epoch, static data complete):
 *    Coverage on disk is already current for this season. The sidecar is submitted for
 *    background loading into the store so the initial snapshot has complete data when
 *    the player arrives. No SS queries, no coverage deltas, no tick-thread budget.
 *
 *  RE-DERIVE (epoch changed, static data complete):
 *    The season changed since the last run. Coverage is re-computed from the cached
 *    static climate samples using a single SS call per chunk. No heightmap reads.
 *    Coverage deltas are emitted for Nova via onChunkCoverageComputed.
 *
 *  FULL SCAN (static data missing or incomplete):
 *    New or partially-analysed terrain. Surface heights are read from the .mca
 *    heightmap, biomes sampled from the world, static samples persisted to the
 *    sidecar for future runs, then coverage derived as in the re-derive path.
 *
 * This means that after the first complete world analysis pass, subsequent server
 * restarts in the same season cost only sidecar loads (IO-thread only, zero SS calls),
 * while season transitions cost one SS call per unloaded chunk with no disk reads.
 */
public final class UnloadedChunkCoverageBuilder {
    private static final Gson GSON = new Gson();
    private static final Pattern REGION_NAME = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
    private static final int SAMPLE_LOCAL_X = 8;
    private static final int SAMPLE_LOCAL_Z = 8;

    private final SeasonCacheConfig config;
    private final SeasonProvider provider;
    private final SeasonEpochService epochService;
    private final ChunkSeasonStore store;
    private final RegionIOThread ioThread;

    private final ArrayDeque<Path> pendingRegions = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<RegionBatch> completedBatches = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inflightRegions = new AtomicInteger(0);

    private volatile boolean shutdownRequested = false;

    private RegionBatch currentBatch = null;
    private int currentBatchIndex = 0;

    private RuntimeTypes.BudgetProfile activeProfile = RuntimeTypes.BudgetProfile.LOW;
    private boolean active = false;
    private int targetEpoch = 0;
    private int totalFiles = 0;
    private int processedFiles = 0;
    private int preWarmCount = 0;
    private int reDeriveCount = 0;
    private int fullScanCount = 0;
    private RuntimeTypes.CoverageSeasonSnapshot activeSeasonSnapshot;

    public UnloadedChunkCoverageBuilder(
            SeasonCacheConfig config,
            SeasonProvider provider,
            SeasonEpochService epochService,
            ChunkSeasonStore store,
            RegionIOThread ioThread
    ) {
        this.config = config;
        this.provider = provider;
        this.epochService = epochService;
        this.store = store;
        this.ioThread = ioThread;
    }

    public boolean isActive() { return this.active; }
    public int totalFiles() { return this.totalFiles; }
    public int processedFiles() { return this.processedFiles; }
    public RuntimeTypes.BudgetProfile activeProfile() { return this.activeProfile; }

    /**
     * Re-sorts the pending region queue nearest-first from the given player chunk
     * position. Called once at player join / overworld re-entry while a build is
     * still in progress. Has no effect if the queue has already been drained.
     *
     * The sort is on the pending Path deque only — IO tasks already submitted to
     * the IO thread are unaffected, but on a cold start the full drain happens on
     * the first tick after server start so the player join typically fires before
     * any regions have been submitted. On a warm restart the queue is empty
     * (pre-warm tasks go directly to the IO thread) so the sort is a no-op.
     *
     * Region coordinates are extracted from the filename (r.X.Z.mca) so no disk
     * access is required. The player's region coordinate is derived from their
     * chunk position, one region = 32 chunks.
     */
    public void prioritizeFromPlayer(ChunkPos playerChunk) {
        if (this.pendingRegions.isEmpty()) return;

        int playerRegionX = Math.floorDiv(playerChunk.x, 32);
        int playerRegionZ = Math.floorDiv(playerChunk.z, 32);

        List<Path> sorted = new ArrayList<>(this.pendingRegions);
        sorted.sort((a, b) -> {
            long da = regionDistanceSq(a.getFileName().toString(), playerRegionX, playerRegionZ);
            long db = regionDistanceSq(b.getFileName().toString(), playerRegionX, playerRegionZ);
            return Long.compare(da, db);
        });

        this.pendingRegions.clear();
        this.pendingRegions.addAll(sorted);

        SeasonCacheMod.LOGGER.info(
                "Season Cache: re-sorted {} pending regions nearest-first from player region [{}, {}].",
                sorted.size(), playerRegionX, playerRegionZ);
    }

    private static long regionDistanceSq(String filename, int playerRegionX, int playerRegionZ) {
        Matcher m = REGION_NAME.matcher(filename);
        if (!m.matches()) return Long.MAX_VALUE;
        long dx = Long.parseLong(m.group(1)) - playerRegionX;
        long dz = Long.parseLong(m.group(2)) - playerRegionZ;
        return dx * dx + dz * dz;
    }

    public void start(ServerWorld world, RuntimeTypes.BudgetProfile profile) {
        this.pendingRegions.clear();
        this.completedBatches.clear();
        this.currentBatch = null;
        this.currentBatchIndex = 0;
        this.processedFiles = 0;
        this.totalFiles = 0;
        this.preWarmCount = 0;
        this.reDeriveCount = 0;
        this.fullScanCount = 0;
        this.activeProfile = profile;
        this.targetEpoch = this.epochService.currentEpoch(world);
        this.activeSeasonSnapshot = this.provider.snapshotCoverageSeason(world);

        Path regionDir = getRegionDirectory(world);
        if (!Files.isDirectory(regionDir)) {
            this.active = false;
            return;
        }

        try {
            // Sort by distance from world spawn as a reasonable default — the player
            // will almost always join near spawn. prioritizeFromPlayer() can re-sort
            // whatever hasn't been submitted yet once the actual login origin is known.
            ChunkPos spawnChunk = new ChunkPos(world.getSpawnPos());
            int spawnRegionX = Math.floorDiv(spawnChunk.x, 32);
            int spawnRegionZ = Math.floorDiv(spawnChunk.z, 32);

            List<Path> files = Files.list(regionDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> REGION_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingLong(p ->
                            regionDistanceSq(p.getFileName().toString(), spawnRegionX, spawnRegionZ)))
                    .toList();
            this.pendingRegions.addAll(files);
            this.totalFiles = files.size();
            this.active = !files.isEmpty();
        } catch (Exception e) {
            this.active = false;
            SeasonCacheMod.LOGGER.warn("Season Cache: failed to enumerate region files for unloaded coverage build.", e);
        }
    }

    public void shutdown() {
        this.shutdownRequested = true;
    }

    public void tick(ServerWorld world) {
        if (!this.active) return;

        int currentEpoch = this.epochService.currentEpoch(world);
        if (currentEpoch != this.targetEpoch) {
            start(world, this.activeProfile);
            return;
        }

        RuntimeTypes.Budget budget = this.config.budgetFor(this.activeProfile);
        long startNs = System.nanoTime();
        long maxMillis = Math.max(5L, budget.maxMillisPerTick());

        if (!this.pendingRegions.isEmpty()) {
            int bottomY = world.getBottomY();
            int worldHeight = world.getHeight();
            // Submit at most regionsPerTick regions per tick so pendingRegions is
            // not fully drained on the first tick. This keeps unsubmitted regions
            // available for prioritizeFromPlayer() to re-sort when a player joins,
            // ensuring the remaining IO submissions proceed in player-distance order.
            int submissionsThisTick = 0;
            int maxSubmissions = budget.regionsPerTick();
            while (!this.pendingRegions.isEmpty() && submissionsThisTick < maxSubmissions) {
                submitRegionToIOThread(world, this.pendingRegions.removeFirst(), bottomY, worldHeight);
                submissionsThisTick++;
            }
        }

        while (elapsedMillis(startNs) < maxMillis) {
            if (this.currentBatch == null) {
                RegionBatch next = this.completedBatches.poll();
                if (next == null) break;
                this.currentBatch = next;
                this.currentBatchIndex = 0;
            }

            this.currentBatchIndex = drainBatch(world, this.currentBatch, this.currentBatchIndex, currentEpoch, startNs, maxMillis);
            if (this.currentBatchIndex >= this.currentBatch.entries().size()) {
                switch (this.currentBatch.path()) {
                    case PRE_WARM  -> this.preWarmCount++;
                    case RE_DERIVE -> this.reDeriveCount++;
                    case FULL_SCAN -> this.fullScanCount++;
                }
                this.currentBatch = null;
                this.currentBatchIndex = 0;
                this.processedFiles++;
            }
        }

        if (this.pendingRegions.isEmpty()
                && this.inflightRegions.get() == 0
                && this.completedBatches.isEmpty()
                && this.currentBatch == null) {
            this.active = false;
            this.store.flushDirty();
            SeasonCacheMod.LOGGER.info(
                    "Season Cache: coverage pass complete for epoch {} — {} regions total " +
                    "({} pre-warmed, {} re-derived, {} full-scanned).",
                    this.targetEpoch, this.totalFiles,
                    this.preWarmCount, this.reDeriveCount, this.fullScanCount);
        }
    }

    private void submitRegionToIOThread(ServerWorld world, Path regionPath, int bottomY, int worldHeight) {
        Matcher matcher = REGION_NAME.matcher(regionPath.getFileName().toString());
        if (!matcher.matches()) return;

        int regionX = Integer.parseInt(matcher.group(1));
        int regionZ = Integer.parseInt(matcher.group(2));
        Path sidecarPath = sidecarPath(world, regionX, regionZ);

        this.inflightRegions.incrementAndGet();
        this.ioThread.submitHeightmapRead(() -> {
            RegionBatch batch;
            try {
                if (this.shutdownRequested) {
                    batch = new RegionBatch(List.of(), RegionPath.PRE_WARM);
                } else {
                    StaticRegionSnapshot staticSnapshot = readStaticSnapshot(sidecarPath);
                    boolean staticComplete = !staticSnapshot.entries().isEmpty()
                            && (staticSnapshot.knownChunkCount() <= 0
                                    || staticSnapshot.entries().size() >= staticSnapshot.knownChunkCount());
                    boolean sameEpoch = staticSnapshot.dataEpoch() == this.targetEpoch;

                    if (sameEpoch && staticComplete) {
                        // PRE-WARM: coverage is already current on disk. Trigger a background
                        // sidecar load so the snapshot has complete data at player login.
                        // No SS queries, no coverage deltas — nothing to process on the tick thread.
                        this.store.preWarmRegion(world, regionX, regionZ);
                        batch = new RegionBatch(List.of(), RegionPath.PRE_WARM);
                    } else if (staticComplete) {
                        // RE-DERIVE: epoch changed, but static geography is fully cached.
                        // Re-classify each chunk with one SS call — no heightmap reads needed.
                        batch = new RegionBatch(new ArrayList<>(staticSnapshot.entries().values()), RegionPath.RE_DERIVE);
                    } else {
                        // FULL SCAN: static samples missing or incomplete for this region.
                        // Read surface heights from the .mca file then merge with any partial cache.
                        List<RegionHeightmapReader.ChunkSurfaceEntry> heights = RegionHeightmapReader.readSurfaceHeights(
                                regionPath, regionX, regionZ, bottomY, worldHeight);
                        List<ChunkClimateWorkEntry> merged = new ArrayList<>(heights.size());
                        for (RegionHeightmapReader.ChunkSurfaceEntry heightEntry : heights) {
                            ChunkClimateWorkEntry cached = staticSnapshot.entries().get(heightEntry.chunkPos().toLong());
                            if (cached != null) {
                                merged.add(cached);
                            } else {
                                merged.add(new ChunkClimateWorkEntry(heightEntry.chunkPos(), heightEntry.surfaceY(), null));
                            }
                        }
                        batch = new RegionBatch(merged, RegionPath.FULL_SCAN);
                    }
                }
            } catch (Exception e) {
                SeasonCacheMod.LOGGER.warn("Season Cache: unloaded coverage batch failed for {}.", regionPath.getFileName(), e);
                batch = new RegionBatch(List.of(), RegionPath.FULL_SCAN);
            }

            this.completedBatches.offer(batch);
            this.inflightRegions.decrementAndGet();
        });
    }

    private int drainBatch(ServerWorld world, RegionBatch batch, int fromIndex, int currentEpoch, long startNs, long maxMillis) {
        int i = fromIndex;
        while (i < batch.entries().size() && elapsedMillis(startNs) < maxMillis) {
            processEntry(world, batch.entries().get(i), currentEpoch);
            i++;
        }
        return i;
    }

    private void processEntry(ServerWorld world, ChunkClimateWorkEntry entry, int currentEpoch) {
        ChunkPos chunkPos = entry.chunkPos();
        if (world.isChunkLoaded(chunkPos.x, chunkPos.z)) return;

        int surfaceY = entry.surfaceY();
        if (surfaceY == RegionHeightmapReader.UNAVAILABLE) {
            surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    chunkPos.getStartX() + SAMPLE_LOCAL_X, chunkPos.getStartZ() + SAMPLE_LOCAL_Z) - 1;
        }
        surfaceY = Math.max(surfaceY, world.getBottomY());
        int worldX = chunkPos.getStartX() + SAMPLE_LOCAL_X;
        int worldZ = chunkPos.getStartZ() + SAMPLE_LOCAL_Z;
        BlockPos samplePos = new BlockPos(worldX, surfaceY, worldZ);

        RegistryEntry<Biome> biomeEntry = null;
        String biomeId = entry.biomeId();
        if (biomeId != null && !biomeId.isBlank()) {
            try {
                Identifier id = Identifier.of(biomeId);
                var biomeRegistry = world.getRegistryManager().get(RegistryKeys.BIOME);
                var opt = biomeRegistry.getEntry(RegistryKey.of(RegistryKeys.BIOME, id));
                if (opt.isPresent()) {
                    biomeEntry = opt.get();
                }
            } catch (Exception ignored) {
            }
        }

        if (biomeEntry == null) {
            biomeEntry = world.getBiome(samplePos);
            biomeId = biomeEntry.getKey().map(key -> key.getValue().toString()).orElse(null);
            if (biomeId == null || biomeId.isBlank()) return;
            this.store.setStaticClimateSample(world, chunkPos, biomeId, surfaceY);
        }

        RuntimeTypes.CoverageSample sample = this.provider.snapshotCoverageSample(world, samplePos, biomeEntry);
        boolean snowy = this.provider.shouldSampleSnowCoverage(this.activeSeasonSnapshot, sample);
        this.store.setCoverageState(world, chunkPos, currentEpoch, snowy);
        SeasonCacheMod.get().onChunkCoverageComputed(world, chunkPos, currentEpoch, snowy);
    }

    private static long elapsedMillis(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    private static Path getRegionDirectory(ServerWorld world) {
        Path root = world.getServer().getSavePath(WorldSavePath.ROOT);
        String dimPath = world.getRegistryKey() == World.OVERWORLD ? "region"
                : "dimensions/" + world.getRegistryKey().getValue().getNamespace()
                  + "/" + world.getRegistryKey().getValue().getPath() + "/region";
        return root.resolve(dimPath);
    }

    private static Path sidecarPath(ServerWorld world, int regionX, int regionZ) {
        String dimPath = world.getRegistryKey().getValue().getPath();
        return world.getServer().getSavePath(WorldSavePath.ROOT)
                .resolve("seasoncache")
                .resolve(dimPath)
                .resolve("r." + regionX + "." + regionZ + ".json");
    }

    private static StaticRegionSnapshot readStaticSnapshot(Path sidecarPath) {
        if (!Files.exists(sidecarPath)) {
            return StaticRegionSnapshot.EMPTY;
        }

        try (Reader reader = Files.newBufferedReader(sidecarPath)) {
            RegionSidecarDisk disk = GSON.fromJson(reader, RegionSidecarDisk.class);
            if (disk == null) return StaticRegionSnapshot.EMPTY;
            Map<Long, ChunkClimateWorkEntry> entries = new HashMap<>();
            if (disk.staticClimateSamples != null) {
                for (StaticClimateEntryDisk entry : disk.staticClimateSamples) {
                    if (entry == null || entry.biomeId == null || entry.biomeId.isBlank()) continue;
                    entries.put(entry.chunkKey, new ChunkClimateWorkEntry(new ChunkPos(entry.chunkKey), entry.surfaceY, entry.biomeId));
                }
            }
            return new StaticRegionSnapshot(disk.dataEpoch, disk.knownChunkCount, entries);
        } catch (Exception e) {
            return StaticRegionSnapshot.EMPTY;
        }
    }

    /** Identifies which dispatch path produced a batch, for completion counters and logging. */
    private enum RegionPath { PRE_WARM, RE_DERIVE, FULL_SCAN }

    private record RegionBatch(List<ChunkClimateWorkEntry> entries, RegionPath path) {
    }

    private record ChunkClimateWorkEntry(ChunkPos chunkPos, int surfaceY, String biomeId) {
    }

    private record StaticRegionSnapshot(int dataEpoch, int knownChunkCount, Map<Long, ChunkClimateWorkEntry> entries) {
        private static final StaticRegionSnapshot EMPTY = new StaticRegionSnapshot(0, 0, Map.of());
    }

    private static final class RegionSidecarDisk {
        int schemaVersion = 0;
        int dataEpoch = 0;
        int knownChunkCount = 0;
        StaticClimateEntryDisk[] staticClimateSamples = new StaticClimateEntryDisk[0];
    }

    private static final class StaticClimateEntryDisk {
        long chunkKey;
        String biomeId;
        int surfaceY;
    }
}
