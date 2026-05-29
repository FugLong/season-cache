package com.seasoncache.core;

import com.google.gson.Gson;
import com.seasoncache.SeasonCacheMod;
import com.seasoncache.config.SeasonCacheConfig;
import com.seasoncache.core.io.RegionIOThread;
import com.seasoncache.core.store.ChunkSeasonStore;
import com.seasoncache.integration.SeasonProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

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
 * Background rule pre-builder for unloaded terrain.
 *
 * This pass is no longer a current-epoch coverage builder. It only ensures that
 * persistent static samples and persistent 12-season chunk rules exist for explored
 * chunks found in region files. Live chunk snow booleans are derived from the rule
 * whenever clients or the runtime need them.
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
    private volatile int generation = 0;

    private RegionBatch currentBatch = null;
    private int currentBatchIndex = 0;

    private RuntimeTypes.BudgetProfile activeProfile = RuntimeTypes.BudgetProfile.LOW;
    private boolean active = false;
    private int totalFiles = 0;
    private int processedFiles = 0;
    private int staticOnlyCount = 0;
    private int fullScanCount = 0;

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

    public void prioritizeFromPlayer(ChunkPos playerChunk) {
        if (this.pendingRegions.isEmpty()) return;

        int playerRegionX = Math.floorDiv(playerChunk.x(), 32);
        int playerRegionZ = Math.floorDiv(playerChunk.z(), 32);

        List<Path> sorted = new ArrayList<>(this.pendingRegions);
        sorted.sort((a, b) -> Long.compare(
                regionDistanceSq(a.getFileName().toString(), playerRegionX, playerRegionZ),
                regionDistanceSq(b.getFileName().toString(), playerRegionX, playerRegionZ)
        ));

        this.pendingRegions.clear();
        this.pendingRegions.addAll(sorted);
    }

    private static long regionDistanceSq(String filename, int playerRegionX, int playerRegionZ) {
        Matcher m = REGION_NAME.matcher(filename);
        if (!m.matches()) return Long.MAX_VALUE;
        long dx = Long.parseLong(m.group(1)) - playerRegionX;
        long dz = Long.parseLong(m.group(2)) - playerRegionZ;
        return dx * dx + dz * dz;
    }

    public void start(ServerLevel world, RuntimeTypes.BudgetProfile profile) {
        this.generation++;
        this.shutdownRequested = false;
        this.pendingRegions.clear();
        this.completedBatches.clear();
        this.inflightRegions.set(0);
        this.currentBatch = null;
        this.currentBatchIndex = 0;
        this.processedFiles = 0;
        this.totalFiles = 0;
        this.staticOnlyCount = 0;
        this.fullScanCount = 0;
        this.activeProfile = profile;

        Path regionDir = getRegionDirectory(world);
        if (!Files.isDirectory(regionDir)) {
            this.active = false;
            return;
        }

        try {
            ChunkPos spawnChunk = ChunkPos.containing(world.getRespawnData().globalPos().pos());
            int spawnRegionX = Math.floorDiv(spawnChunk.x(), 32);
            int spawnRegionZ = Math.floorDiv(spawnChunk.z(), 32);

            List<Path> files = Files.list(regionDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> REGION_NAME.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingLong(path ->
                            regionDistanceSq(path.getFileName().toString(), spawnRegionX, spawnRegionZ)))
                    .toList();

            this.pendingRegions.addAll(files);
            this.totalFiles = files.size();
            this.active = !files.isEmpty();
        } catch (Exception e) {
            this.active = false;
            SeasonCacheMod.LOGGER.warn("Season Cache: failed to enumerate region files for rule prebuild.", e);
        }
    }

    public void shutdown() {
        this.shutdownRequested = true;
    }

    public void tick(ServerLevel world) {
        if (!this.active) return;

        RuntimeTypes.Budget budget = this.config.budgetFor(this.activeProfile);
        long startNs = System.nanoTime();
        long maxMillis = Math.max(5L, budget.maxMillisPerTick());

        if (!this.pendingRegions.isEmpty()) {
            int bottomY = world.getMinY();
            int worldHeight = world.getHeight();
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

            this.currentBatchIndex = drainBatch(world, this.currentBatch, this.currentBatchIndex, startNs, maxMillis);
            if (this.currentBatchIndex >= this.currentBatch.entries().size()) {
                switch (this.currentBatch.path()) {
                    case STATIC_ONLY -> this.staticOnlyCount++;
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
                    "Season Cache: rule prebuild complete — {} regions total ({} static-only, {} full-scanned).",
                    this.totalFiles, this.staticOnlyCount, this.fullScanCount);
        }
    }

    private void submitRegionToIOThread(ServerLevel world, Path regionPath, int bottomY, int worldHeight) {
        Matcher matcher = REGION_NAME.matcher(regionPath.getFileName().toString());
        if (!matcher.matches()) return;

        int regionX = Integer.parseInt(matcher.group(1));
        int regionZ = Integer.parseInt(matcher.group(2));
        Path sidecarPath = sidecarPath(world, regionX, regionZ);

        final int runGeneration = this.generation;
        this.inflightRegions.incrementAndGet();
        this.ioThread.submitHeightmapRead(() -> {
            RegionBatch batch;
            try {
                if (this.shutdownRequested) {
                    batch = new RegionBatch(List.of(), RegionPath.STATIC_ONLY, regionX, regionZ, 0);
                } else {
                    StaticRegionSnapshot staticSnapshot = readStaticSnapshot(sidecarPath);
                    boolean staticComplete = staticSnapshot.knownChunkCount() > 0
                            && !staticSnapshot.entries().isEmpty()
                            && staticSnapshot.entries().size() >= staticSnapshot.knownChunkCount();

                    if (staticComplete) {
                        batch = new RegionBatch(new ArrayList<>(staticSnapshot.entries().values()),
                                RegionPath.STATIC_ONLY, regionX, regionZ, staticSnapshot.knownChunkCount());
                    } else {
                        List<RegionHeightmapReader.ChunkSurfaceEntry> heights = RegionHeightmapReader.readSurfaceHeights(
                                regionPath, regionX, regionZ, bottomY, worldHeight);
                        List<ChunkClimateWorkEntry> merged = new ArrayList<>(heights.size());
                        for (RegionHeightmapReader.ChunkSurfaceEntry heightEntry : heights) {
                            ChunkClimateWorkEntry cached = staticSnapshot.entries().get(heightEntry.chunkPos().pack());
                            if (cached != null) {
                                merged.add(cached);
                            } else {
                                merged.add(new ChunkClimateWorkEntry(heightEntry.chunkPos(), heightEntry.surfaceY(), null));
                            }
                        }
                        batch = new RegionBatch(merged, RegionPath.FULL_SCAN, regionX, regionZ, heights.size());
                    }
                }
            } catch (Exception e) {
                SeasonCacheMod.LOGGER.warn("Season Cache: unloaded rule prebuild batch failed for {}.", regionPath.getFileName(), e);
                batch = new RegionBatch(List.of(), RegionPath.FULL_SCAN, regionX, regionZ, 0);
            }

            if (this.generation == runGeneration) {
                this.completedBatches.offer(batch);
            }
            this.inflightRegions.decrementAndGet();
        });
    }

    private int drainBatch(ServerLevel world, RegionBatch batch, int fromIndex, long startNs, long maxMillis) {
        int i = fromIndex;
        if (batch.knownChunkCount() > 0) {
            this.store.setKnownChunkCount(world, batch.regionX(), batch.regionZ(), batch.knownChunkCount());
        }
        while (i < batch.entries().size() && elapsedMillis(startNs) < maxMillis) {
            processEntry(world, batch.entries().get(i));
            i++;
        }
        return i;
    }

    private void processEntry(ServerLevel world, ChunkClimateWorkEntry entry) {
        ChunkPos chunkPos = entry.chunkPos();
        if (world.getChunkSource().hasChunk(chunkPos.x(), chunkPos.z())) return;

        int surfaceY = entry.surfaceY();
        if (surfaceY == RegionHeightmapReader.UNAVAILABLE) {
            surfaceY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    chunkPos.getMinBlockX() + SAMPLE_LOCAL_X, chunkPos.getMinBlockZ() + SAMPLE_LOCAL_Z) - 1;
        }
        surfaceY = Math.max(surfaceY, world.getMinY());
        int worldX = chunkPos.getMinBlockX() + SAMPLE_LOCAL_X;
        int worldZ = chunkPos.getMinBlockZ() + SAMPLE_LOCAL_Z;
        BlockPos samplePos = new BlockPos(worldX, surfaceY, worldZ);

        Holder<Biome> biomeEntry = null;
        String biomeId = entry.biomeId();
        if (biomeId != null && !biomeId.isBlank()) {
            try {
                Identifier id = Identifier.parse(biomeId);
                biomeEntry = world.registryAccess().lookupOrThrow(Registries.BIOME)
                        .get(ResourceKey.create(Registries.BIOME, id))
                        .orElse(null);
            } catch (Exception ignored) {
            }
        }

        if (biomeEntry == null) {
            biomeEntry = world.getBiome(samplePos);
            biomeId = biomeEntry.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
            if (biomeId == null || biomeId.isBlank()) return;
        }

        this.store.setStaticClimateSample(world, chunkPos, biomeId, surfaceY);

        RuntimeTypes.ChunkSeasonRule rule = this.store.getChunkSeasonRule(world, chunkPos);
        if (rule == null) {
            rule = ChunkSeasonReconciler.buildChunkSeasonRule(
                    samplePos, biomeEntry, SeasonCacheMod.get().seasonRuleConfig(), world.getSeaLevel());
            this.store.setChunkSeasonRule(world, chunkPos, rule);
        }

        int currentEpoch = this.epochService.currentEpoch(world);
        int seasonIndex = SeasonCacheMod.get().seasonRuleConfig().seasonIndex(this.provider.snapshot(world).seasonKey());
        SeasonCacheMod.get().onChunkCoverageComputed(world, chunkPos, currentEpoch, rule.isSnowyInSeason(seasonIndex));
    }

    private static long elapsedMillis(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    private static Path getRegionDirectory(ServerLevel world) {
        Path root = world.getServer().getWorldPath(LevelResource.ROOT);
        String dimPath = world.dimension() == Level.OVERWORLD ? "region"
                : "dimensions/" + world.dimension().identifier().getNamespace()
                + "/" + world.dimension().identifier().getPath() + "/region";
        return root.resolve(dimPath);
    }

    private static Path sidecarPath(ServerLevel world, int regionX, int regionZ) {
        String dimPath = world.dimension().identifier().getPath();
        return world.getServer().getWorldPath(LevelResource.ROOT)
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
                    entries.put(entry.chunkKey, new ChunkClimateWorkEntry(ChunkPos.unpack(entry.chunkKey), entry.surfaceY, entry.biomeId));
                }
            }
            return new StaticRegionSnapshot(disk.knownChunkCount, entries);
        } catch (Exception e) {
            return StaticRegionSnapshot.EMPTY;
        }
    }

    private enum RegionPath { STATIC_ONLY, FULL_SCAN }

    private record RegionBatch(List<ChunkClimateWorkEntry> entries, RegionPath path, int regionX, int regionZ, int knownChunkCount) {
    }

    private record ChunkClimateWorkEntry(ChunkPos chunkPos, int surfaceY, String biomeId) {
    }

    private record StaticRegionSnapshot(int knownChunkCount, Map<Long, ChunkClimateWorkEntry> entries) {
        private static final StaticRegionSnapshot EMPTY = new StaticRegionSnapshot(0, Map.of());
    }

    private static final class RegionSidecarDisk {
        int schemaVersion = 0;
        int knownChunkCount = 0;
        StaticClimateEntryDisk[] staticClimateSamples = new StaticClimateEntryDisk[0];
    }

    private static final class StaticClimateEntryDisk {
        long chunkKey;
        String biomeId;
        int surfaceY;
    }
}
