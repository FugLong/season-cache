package com.seasoncache;

import com.seasoncache.command.SeasonCacheCommands;
import com.seasoncache.config.SeasonCacheConfig;
import com.seasoncache.core.ChunkSeasonReconciler;
import com.seasoncache.core.RuleDerivationThread;
import com.seasoncache.core.RuntimeTypes;
import com.seasoncache.core.SeasonEpochService;
import com.seasoncache.core.SereneSeasonTomlConfig;
import com.seasoncache.core.UnloadedChunkCoverageBuilder;
import com.seasoncache.core.io.RegionIOThread;
import com.seasoncache.core.store.ChunkSeasonStore;
import com.seasoncache.integration.SeasonProvider;
import com.seasoncache.integration.SereneAwareSeasonProvider;
import com.seasoncache.network.SeasonCacheNetworking;
import com.seasoncache.server.SeasonCacheSyncManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class SeasonCacheMod implements ModInitializer {
    public static final String MOD_ID = "seasoncache";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static SeasonCacheMod instance;

    private static final int FIRST_PLAYER_ACTIVATION_DELAY_TICKS = 40;
    private static final int SEASON_CHANGE_SWEEP_DELAY_TICKS = 40;
    /** Avoid world queries during {@link ServerChunkEvents#CHUNK_LOAD} (re-entrancy with C2ME). */
    private static final int DERIVATION_SCHEDULE_DRAIN_PER_TICK = 16;

    private SeasonCacheConfig config;
    private SeasonProvider seasonProvider;
    private SeasonEpochService epochService;
    private ChunkSeasonStore store;
    private ChunkSeasonReconciler reconciler;
    private UnloadedChunkCoverageBuilder coverageBuilder;
    private RegionIOThread ioThread;
    private SeasonCacheSyncManager syncManager;
    private Set<Identifier> seasonalColdOverrides = Set.of();
    private RuntimeTypes.SeasonRuleConfig seasonRuleConfig =
            new RuntimeTypes.SeasonRuleConfig(true, java.util.Map.of(), java.util.List.of(), "", null);

    private Integer lastKnownEpoch = null;
    private int neighbourhoodTick = 0;
    private final Map<ResourceKey<Level>, Set<Long>> loadedChunkKeys = new HashMap<>();
    private final ArrayDeque<Long> loadedSweepQueue = new ArrayDeque<>();
    private final Set<Long> loadedSweepQueued = new HashSet<>();
    private int loadedSweepEpoch = 0;

    // Rule derivation — for chunks that load without a cached rule.
    // Main thread resolves world-dependent inputs, derivation thread runs
    // the 12-season temperature computation, results drain back each tick.
    private RuleDerivationThread derivationThread;
    private final ConcurrentLinkedQueue<RuleDerivationThread.DerivationResult> derivationResults
            = new ConcurrentLinkedQueue<>();
    private final Set<Long> pendingDerivations = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<PendingDerivation> derivationScheduleQueue = new ConcurrentLinkedQueue<>();

    private boolean pendingStartupInvalidation = false;
    private boolean runtimeActivated = false;
    private int activationTicksRemaining = -1;
    private int seasonChangeSweepTicksRemaining = -1;
    private String pendingSeasonKey = null;
    private int pendingSeasonEpoch = 0;

    public static SeasonCacheMod get() { return instance; }

    public SeasonCacheConfig config() { return this.config; }
    public SeasonProvider seasonProvider() { return this.seasonProvider; }
    public SeasonEpochService epochService() { return this.epochService; }
    public ChunkSeasonStore store() { return this.store; }
    public UnloadedChunkCoverageBuilder coverageBuilder() { return this.coverageBuilder; }
    public RuleDerivationThread derivationThread() { return this.derivationThread; }
    public int pendingDerivationCount() { return this.pendingDerivations.size(); }
    public RegionIOThread ioThread() { return this.ioThread; }
    public SeasonCacheSyncManager syncManager() { return this.syncManager; }
    public Set<Identifier> seasonalColdOverrides() { return this.seasonalColdOverrides; }
    public RuntimeTypes.SeasonRuleConfig seasonRuleConfig() { return this.seasonRuleConfig; }

    @Override
    public void onInitialize() {
        instance = this;

        this.config = SeasonCacheConfig.load();
        this.seasonProvider = new SereneAwareSeasonProvider();
        this.epochService = new SeasonEpochService(this.config, this.seasonProvider);
        this.ioThread = new RegionIOThread();
        this.store = new ChunkSeasonStore();
        this.store.setIOThread(this.ioThread);
        this.syncManager = new SeasonCacheSyncManager(this.store);
        this.reconciler = new ChunkSeasonReconciler(this.config, this.seasonProvider, this.epochService, this.store);
        this.coverageBuilder = new UnloadedChunkCoverageBuilder(this.config, this.seasonProvider, this.epochService, this.store, this.ioThread);

        SeasonCacheNetworking.registerPayloadTypes();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                SeasonCacheCommands.register(dispatcher));

        ServerChunkEvents.CHUNK_LOAD.register((world, chunk, newTick) -> onChunkLoad(world, chunk));
        ServerChunkEvents.CHUNK_UNLOAD.register(this::onChunkUnload);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> this.onPlayerJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> this.syncManager.removePlayer(handler.getPlayer()));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(this::onPlayerChangeWorld);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            this.ioThread.start();
            try {
                this.derivationThread = new RuleDerivationThread(this.derivationResults);
                this.derivationThread.start();
                LOGGER.info("Season Cache: dedicated rule derivation thread started.");
            } catch (OutOfMemoryError e) {
                this.derivationThread = null;
                LOGGER.warn("Season Cache: could not allocate dedicated derivation thread (OOM). " +
                        "Falling back to IO thread for rule derivation.");
            }
            LOGGER.info("Season Cache IO thread started.");

            this.seasonRuleConfig = SereneSeasonTomlConfig.load(server);
            String previousRuleHash = SereneSeasonTomlConfig.readCachedHash(server);
            if (previousRuleHash != null && !previousRuleHash.equals(this.seasonRuleConfig.hash())) {
                LOGGER.info("Season Cache: rule inputs changed ({} -> {}). Deferring rule invalidation until runtime activation.",
                        previousRuleHash, this.seasonRuleConfig.hash());
                this.pendingStartupInvalidation = true;
            }
            SereneSeasonTomlConfig.writeCachedHash(server, this.seasonRuleConfig.hash());

            ServerLevel overworld = server.overworld();
            if (overworld != null) {
                try {
                    var allBiomes = server.registryAccess()
                            .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                            .listElements()
                            .toList();
                    this.seasonalColdOverrides = this.seasonProvider.buildSeasonalOverrideSet(overworld, allBiomes);
                } catch (Exception e) {
                    this.seasonalColdOverrides = Set.of();
                }
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!this.config.enabled) return;

            tickRuntimeActivation(server);
            if (!this.runtimeActivated) return;

            ServerLevel overworld = server.overworld();
            if (overworld == null) return;

            this.coverageBuilder.tick(overworld);
            drainScheduledDerivations(server);
            drainDerivationResults(overworld);
            drainLoadedChunkSweep(overworld);
            this.syncManager.tick(server);

            this.neighbourhoodTick++;
            if (this.neighbourhoodTick >= RegionIOThread.NEIGHBOURHOOD_REFRESH_TICKS) {
                this.neighbourhoodTick = 0;
                refreshPlayerNeighbourhood(server);
            }

            int currentEpoch = this.epochService.currentEpoch(overworld);
            if (this.lastKnownEpoch == null) {
                this.lastKnownEpoch = currentEpoch;
            } else if (currentEpoch != this.lastKnownEpoch) {
                if (this.seasonChangeSweepTicksRemaining < 0) {
                    // Season just changed — record intent and defer sweep so SS has time
                    // to fully commit its internal state before we reconcile blocks.
                    this.pendingSeasonKey = this.seasonProvider.snapshot(overworld).seasonKey();
                    this.pendingSeasonEpoch = currentEpoch;
                    this.seasonChangeSweepTicksRemaining = SEASON_CHANGE_SWEEP_DELAY_TICKS;
                    this.lastKnownEpoch = currentEpoch;
                    LOGGER.info("Season Cache: season transition to {} detected. Sweep deferred {} ticks.",
                            this.pendingSeasonKey, SEASON_CHANGE_SWEEP_DELAY_TICKS);
                }
            }

            if (this.seasonChangeSweepTicksRemaining > 0) {
                this.seasonChangeSweepTicksRemaining--;
            } else if (this.seasonChangeSweepTicksRemaining == 0) {
                this.seasonChangeSweepTicksRemaining = -1;
                onSeasonChanged(server, this.pendingSeasonKey, this.pendingSeasonEpoch);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            this.store.flushAll();
            this.coverageBuilder.shutdown();
            if (this.derivationThread != null) {
                this.derivationThread.shutdown();
            }
            this.ioThread.shutdown();
            LOGGER.info("Season Cache: IO and derivation threads shut down.");
        });

        LOGGER.info("Season Cache initialized. provider={}", this.seasonProvider.getProviderId());
    }

    private void onChunkLoad(ServerLevel world, LevelChunk chunk) {
        if (!this.config.enabled) return;
        if (this.config.overworldOnly && world.dimension() != Level.OVERWORLD) return;

        ChunkPos chunkPos = chunk.getPos();
        this.loadedChunkKeys.computeIfAbsent(world.dimension(), k -> new HashSet<>()).add(chunkPos.pack());

        if (!this.runtimeActivated) return;

        int currentEpoch = this.epochService.currentEpoch(world);

        if (!this.store.hasChunkSeasonRule(world, chunkPos)) {
            // No rule yet — schedule derivation on a later tick (not during CHUNK_LOAD).
            scheduleDerivation(world, chunkPos);
        } else {
            if (!this.store.isChunkSwept(world, chunkPos, currentEpoch)) {
                // Rule exists but not yet confirmed swept this epoch — enqueue for sweep.
                enqueueChunkForSweep(chunkPos, currentEpoch);
            }
            // Always send the authoritative state to CompSnow on load, even if already
            // swept. Without this, chunks that skip reconcile (already clean/swept from
            // a prior session) never get an authoritative event and vanilla BiomeSampler
            // fills the shader texture with season-unaware data instead.
            Boolean snowy = this.store.getAuthoritativeChunkSnowState(world, chunkPos, currentEpoch);
            if (snowy != null) {
                this.syncManager.queueChunkStateUpdate(world, chunkPos, currentEpoch, snowy);
            }
        }
    }

    /**
     * Queues rule derivation for a later server tick. Must not run synchronous
     * world access from {@link ServerChunkEvents#CHUNK_LOAD} (deadlocks with C2ME).
     */
    private void scheduleDerivation(ServerLevel world, ChunkPos chunkPos) {
        long key = chunkPos.pack();
        if (!this.pendingDerivations.add(key)) return;
        this.derivationScheduleQueue.offer(new PendingDerivation(world.dimension(), key));
    }

    private void drainScheduledDerivations(MinecraftServer server) {
        int limit = DERIVATION_SCHEDULE_DRAIN_PER_TICK;
        PendingDerivation pending;
        while (limit-- > 0 && (pending = this.derivationScheduleQueue.poll()) != null) {
            ServerLevel world = server.getLevel(pending.dimension());
            if (world == null || !isChunkLoaded(world, pending.chunkKey())) {
                this.pendingDerivations.remove(pending.chunkKey());
                continue;
            }
            submitDerivationTask(world, ChunkPos.unpack(pending.chunkKey()));
        }
    }

    private boolean isChunkLoaded(ServerLevel world, long chunkKey) {
        Set<Long> loaded = this.loadedChunkKeys.get(world.dimension());
        return loaded != null && loaded.contains(chunkKey);
    }

    /**
     * Resolves world-dependent inputs on the main thread, then submits the
     * temperature computation to the derivation thread (or IO thread fallback).
     * Caller must have reserved the chunk via {@link #scheduleDerivation} first.
     */
    private void submitDerivationTask(ServerLevel world, ChunkPos chunkPos) {
        long key = chunkPos.pack();

        int worldX = chunkPos.getMinBlockX() + 8;
        int worldZ = chunkPos.getMinBlockZ() + 8;
        int surfaceY = Math.max(
                world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1,
                world.getMinY());
        BlockPos samplePos = new BlockPos(worldX, surfaceY, worldZ);
        Holder<Biome> biomeEntry = world.getBiome(samplePos);
        String biomeId = biomeEntry.unwrapKey().map(k -> k.identifier().toString()).orElse(null);
        if (biomeId == null || biomeId.isBlank()) {
            this.pendingDerivations.remove(key);
            return;
        }

        RuntimeTypes.StaticChunkClimate staticSample = new RuntimeTypes.StaticChunkClimate(biomeId, surfaceY);
        RuntimeTypes.SeasonRuleConfig ruleConfig = this.seasonRuleConfig;
        RuleDerivationThread.DerivationTask task = new RuleDerivationThread.DerivationTask(
                chunkPos, samplePos, biomeEntry, ruleConfig, staticSample, world.getSeaLevel());

        if (this.derivationThread != null) {
            this.derivationThread.submit(task);
        } else {
            // IO thread fallback — same computation, different executor
            this.ioThread.submitHeightmapRead(() -> {
                RuntimeTypes.ChunkSeasonRule rule = ChunkSeasonReconciler.buildChunkSeasonRule(
                        samplePos, biomeEntry, ruleConfig, world.getSeaLevel());
                this.derivationResults.offer(
                        new RuleDerivationThread.DerivationResult(chunkPos, rule, staticSample));
            });
        }
    }

    /**
     * Drains completed derivation results on the main thread.
     * Writes the rule and static sample to the store and enqueues the chunk
     * for sweep. markChunkSwept is NOT called here — it is called only after
     * reconciler.reconcile actually executes inside drainLoadedChunkSweep.
     */
    private void drainDerivationResults(ServerLevel world) {
        if (this.derivationResults.isEmpty()) return;
        int currentEpoch = this.epochService.currentEpoch(world);
        int limit = 32;
        RuleDerivationThread.DerivationResult result;
        while ((result = this.derivationResults.poll()) != null && limit-- > 0) {
            ChunkPos chunkPos = result.chunkPos();
            this.pendingDerivations.remove(chunkPos.pack());
            RuntimeTypes.ChunkSeasonRule rule = result.rule();
            if (rule == null) continue;
            this.store.setStaticClimateSample(world, chunkPos,
                    result.staticSample().biomeId(), result.staticSample().surfaceY());
            this.store.setChunkSeasonRule(world, chunkPos, rule);
            enqueueChunkForSweep(chunkPos, currentEpoch);
        }
    }

    private void onChunkUnload(ServerLevel world, LevelChunk chunk) {
        if (this.config.overworldOnly && world.dimension() != Level.OVERWORLD) return;
        long key = chunk.getPos().pack();
        Set<Long> set = this.loadedChunkKeys.get(world.dimension());
        if (set != null) set.remove(key);
        this.pendingDerivations.remove(key);
    }

    private void onPlayerJoin(ServerPlayer player) {
        if (!this.config.enabled) return;
        if (player.level().dimension() != Level.OVERWORLD) return;

        armRuntimeActivation();

        if (this.coverageBuilder.isActive()) {
            this.coverageBuilder.prioritizeFromPlayer(player.chunkPosition());
        } else if (this.runtimeActivated) {
            // Builder has finished — restart at LOW budget so this player receives
            // all chunk states via the delta stream. On a warm cache the builder
            // fast-paths through STATIC_ONLY and completes in seconds with minimal
            // tick cost. All players in playerStates receive the deltas.
            this.coverageBuilder.start(
                    (ServerLevel) player.level(), RuntimeTypes.BudgetProfile.LOW);
        }

        if (this.runtimeActivated) {
            int currentEpoch = this.epochService.currentEpoch((ServerLevel) player.level());
            this.syncManager.scheduleInitialSnapshot(player, currentEpoch);
        }
    }

    private void onPlayerChangeWorld(ServerPlayer player, ServerLevel origin, ServerLevel destination) {
        if (!this.config.enabled) return;
        if (destination.dimension() != Level.OVERWORLD) return;

        armRuntimeActivation();

        if (this.coverageBuilder.isActive()) {
            this.coverageBuilder.prioritizeFromPlayer(player.chunkPosition());
        } else if (this.runtimeActivated) {
            // Same as onPlayerJoin — restart at LOW budget for complete delta coverage.
            this.coverageBuilder.start(destination, RuntimeTypes.BudgetProfile.LOW);
        }

        if (this.runtimeActivated) {
            int currentEpoch = this.epochService.currentEpoch(destination);
            this.syncManager.scheduleInitialSnapshot(player, currentEpoch);
        }
    }

    public void onChunkAuthoritativelyReconciled(ServerLevel world, ChunkPos chunkPos, int currentEpoch) {
        Boolean snowy = this.store.getAuthoritativeChunkSnowState(world, chunkPos, currentEpoch);
        if (snowy != null) {
            this.syncManager.queueChunkStateUpdate(world, chunkPos, currentEpoch, snowy);
        }
    }

    public void onChunkCoverageComputed(ServerLevel world, ChunkPos chunkPos, int currentEpoch, boolean snowy) {
        this.syncManager.queueChunkStateUpdate(world, chunkPos, currentEpoch, snowy);
    }

    private void refreshPlayerNeighbourhood(MinecraftServer server) {
        Set<String> neighbourhood = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() != Level.OVERWORLD) continue;
            int regionX = Math.floorDiv((int) player.getX() >> 4, 32);
            int regionZ = Math.floorDiv((int) player.getZ() >> 4, 32);
            String dim = Level.OVERWORLD.identifier().toString();

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    neighbourhood.add(dim + ":" + (regionX + dx) + ":" + (regionZ + dz));
                }
            }
        }
        this.ioThread.updatePlayerNeighbourhood(neighbourhood);
    }

    private void onSeasonChanged(MinecraftServer server, String newSeasonKey, int newEpoch) {
        this.lastKnownEpoch = newEpoch;
        this.syncManager.broadcastEpochInvalidate(server, Level.OVERWORLD, newEpoch);

        ServerLevel overworld = server.overworld();
        if (overworld != null) {
            // Clear sweep records for all loaded chunks so each gets a fresh baseline
            // pass on the new epoch. The new epoch's sweep entries will be written as
            // chunks are processed, and SS resumes authority after each chunk's first pass.
            Set<ChunkPos> candidates = collectLoadedTransitionCandidates(server, overworld);
            for (ChunkPos chunkPos : candidates) {
                this.store.unmarkChunkSwept(overworld, chunkPos);
            }
            enqueueLoadedChunkSweep(server, overworld, newEpoch, "season-change");

            // Re-run the coverage builder so unloaded distant terrain gets its shader
            // coverage state re-broadcast with the new epoch's season index. On a warm
            // cache this is fast — all regions take the STATIC_ONLY path, no IO needed.
            this.coverageBuilder.start(overworld, RuntimeTypes.BudgetProfile.MEDIUM);
        }

        LOGGER.info("Season Cache: season transition detected -> {}. Applied loaded-chunk sweep and restarted coverage builder for epoch {}.",
                newSeasonKey, newEpoch);
    }

    private void armRuntimeActivation() {
        if (this.runtimeActivated || this.activationTicksRemaining >= 0) return;
        this.activationTicksRemaining = FIRST_PLAYER_ACTIVATION_DELAY_TICKS;
        LOGGER.info("Season Cache: first player detected; deferring activation by {} ticks.",
                FIRST_PLAYER_ACTIVATION_DELAY_TICKS);
    }

    private void tickRuntimeActivation(MinecraftServer server) {
        if (this.runtimeActivated) return;
        if (!hasOverworldPlayers(server)) return;
        if (this.activationTicksRemaining < 0) return;

        this.activationTicksRemaining--;
        if (this.activationTicksRemaining > 0) return;
        this.activationTicksRemaining = -1;

        ServerLevel overworld = server.overworld();
        if (overworld == null) return;

        if (this.pendingStartupInvalidation) {
            this.store.invalidateDynamicStateKeepStatic(server, true);
            this.pendingStartupInvalidation = false;
        }

        this.runtimeActivated = true;
        int currentEpoch = this.epochService.currentEpoch(overworld);
        this.lastKnownEpoch = currentEpoch;
        enqueueLoadedChunkSweep(server, overworld, currentEpoch, "activation");

        // Always start the unloaded rule builder on activation. On warm caches it
        // fast-paths through STATIC_ONLY regions in seconds; on cold caches it does
        // the full FULL_SCAN pass. No cold-cache detection needed.
        this.coverageBuilder.start(overworld, RuntimeTypes.BudgetProfile.HIGH);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() == Level.OVERWORLD) {
                this.syncManager.scheduleInitialSnapshot(player, currentEpoch);
            }
        }

        LOGGER.info("Season Cache: runtime activated at epoch {}.", currentEpoch);
    }

    /**
     * Forces all currently loaded chunks to be re-reconciled for the current epoch,
     * regardless of whether they were previously marked clean. Used by /seasoncache sweep
     * to recover from cases where applyChunkTruth ran but produced incorrect results
     * (e.g. after a bug fix that changes removal logic).
     */
    public void forceSweepLoadedChunks(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        int epoch = this.epochService.currentEpoch(overworld);
        Set<ChunkPos> candidates = collectLoadedTransitionCandidates(server, overworld);
        for (ChunkPos chunkPos : candidates) {
            this.store.unmarkChunkCleared(overworld, chunkPos, epoch);
            this.store.unmarkChunkSwept(overworld, chunkPos);
        }
        enqueueLoadedChunkSweep(server, overworld, epoch, "force-sweep");
    }

    private void enqueueLoadedChunkSweep(MinecraftServer server, ServerLevel overworld, int epoch, String reason) {
        ChunkPos origin = ChunkPos.containing(overworld.getRespawnData().globalPos().pos());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() == Level.OVERWORLD) {
                origin = player.chunkPosition();
                break;
            }
        }
        final ChunkPos sortOrigin = origin;

        Set<ChunkPos> candidateChunks = collectLoadedTransitionCandidates(server, overworld);
        List<ChunkPos> reconcileList = new ArrayList<>();
        for (ChunkPos chunkPos : candidateChunks) {
            if (!this.store.hasChunkSeasonRule(overworld, chunkPos)) {
                // No rule yet — schedule derivation; sweep will follow automatically
                scheduleDerivation(overworld, chunkPos);
            } else {
                reconcileList.add(chunkPos);
            }
        }

        reconcileList.sort(Comparator.comparingInt(cp ->
                Math.max(Math.abs(cp.x() - sortOrigin.x()), Math.abs(cp.z() - sortOrigin.z()))));

        // Don't clear the queue — chunks already enqueued via onChunkLoad (which fired
        // between the season change detection and this sweep) must not be dropped.
        // Update the epoch so drainLoadedChunkSweep doesn't discard the queue on mismatch,
        // and append with deduplication via loadedSweepQueued.
        this.loadedSweepEpoch = epoch;
        for (ChunkPos chunkPos : reconcileList) {
            long key = chunkPos.pack();
            if (this.loadedSweepQueued.add(key)) {
                this.loadedSweepQueue.addLast(key);
            }
        }

        LOGGER.info("Season Cache: {} loaded-chunk sweep queued {} chunks for epoch {} from origin [{}, {}].",
                reason, reconcileList.size(), epoch, sortOrigin.x(), sortOrigin.z());
    }

    private void enqueueChunkForSweep(ChunkPos chunkPos, int epoch) {
        if (this.loadedSweepEpoch != epoch) {
            this.loadedSweepQueue.clear();
            this.loadedSweepQueued.clear();
            this.loadedSweepEpoch = epoch;
        }
        long key = chunkPos.pack();
        if (this.loadedSweepQueued.add(key)) {
            this.loadedSweepQueue.addLast(key);
        }
    }

    private void drainLoadedChunkSweep(ServerLevel overworld) {
        if (this.loadedSweepQueue.isEmpty()) return;

        int currentEpoch = this.epochService.currentEpoch(overworld);
        if (currentEpoch != this.loadedSweepEpoch) {
            this.loadedSweepQueue.clear();
            this.loadedSweepQueued.clear();
            this.loadedSweepEpoch = currentEpoch;
            return;
        }

        long maxMillis = this.config.budgetFor(this.config.gameplayBudget).maxMillisPerTick();
        long startNs = System.nanoTime();

        while (!this.loadedSweepQueue.isEmpty()
                && ((System.nanoTime() - startNs) / 1_000_000L) < maxMillis) {
            long key = this.loadedSweepQueue.removeFirst();
            this.loadedSweepQueued.remove(key);
            ChunkPos chunkPos = ChunkPos.unpack(key);

            if (!overworld.getChunkSource().hasChunk(chunkPos.x(), chunkPos.z())) continue;
            if (this.store.isChunkClean(overworld, chunkPos, currentEpoch)) {
                // Already clean — stamp swept so future loads skip it, but don't
                // re-run applyChunkTruth since reconcile already ran for this chunk.
                this.store.markChunkSwept(overworld, chunkPos, currentEpoch);
                continue;
            }

            // reconcile runs applyChunkTruth and calls markChunkCleared.
            // Only stamp swept after it actually executes.
            this.reconciler.reconcile(overworld, chunkPos);
            this.store.markChunkSwept(overworld, chunkPos, currentEpoch);
        }
    }

    private boolean hasOverworldPlayers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() == Level.OVERWORLD) {
                return true;
            }
        }
        return false;
    }

    private Set<ChunkPos> collectLoadedTransitionCandidates(MinecraftServer server, ServerLevel overworld) {
        // Use the full set of tracked loaded chunks rather than a radius around players.
        // The radius approach missed chunks loaded by other players, force-loaded chunks,
        // or large bases that extend beyond the sweep radius — causing partial sweeps.
        // loadedChunkKeys is maintained by onChunkLoad/onChunkUnload and accurately
        // reflects every chunk currently in memory.
        Set<Long> overworldLoaded = this.loadedChunkKeys.getOrDefault(Level.OVERWORLD, Set.of());
        Set<ChunkPos> candidateChunks = new HashSet<>(overworldLoaded.size());
        for (long posLong : overworldLoaded) {
            ChunkPos chunkPos = ChunkPos.unpack(posLong);
            if (overworld.getChunkSource().hasChunk(chunkPos.x(), chunkPos.z())) {
                candidateChunks.add(chunkPos);
            }
        }
        return candidateChunks;
    }

    private record PendingDerivation(ResourceKey<Level> dimension, long chunkKey) {}
}
