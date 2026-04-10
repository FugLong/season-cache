package com.seasoncache;

import com.seasoncache.command.SeasonCacheCommands;
import com.seasoncache.config.SeasonCacheConfig;
import com.seasoncache.core.ChunkSeasonQueue;
import com.seasoncache.core.ChunkSeasonReconciler;
import com.seasoncache.core.RegionPrecacheBuilder;
import com.seasoncache.core.UnloadedChunkCoverageBuilder;
import com.seasoncache.core.SeasonEpochService;
import com.seasoncache.core.RuntimeTypes;
import com.seasoncache.core.io.RegionIOThread;
import com.seasoncache.core.store.ChunkSeasonStore;
import com.seasoncache.integration.SeasonProvider;
import com.seasoncache.integration.SereneAwareSeasonProvider;
import com.seasoncache.network.SeasonCacheNetworking;
import com.seasoncache.server.SeasonCacheSyncManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SeasonCacheMod implements ModInitializer {
    public static final String MOD_ID = "seasoncache";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static SeasonCacheMod instance;

    private SeasonCacheConfig config;
    private SeasonProvider seasonProvider;
    private SeasonEpochService epochService;
    private ChunkSeasonStore store;
    private ChunkSeasonReconciler reconciler;
    private ChunkSeasonQueue reconcileQueue;
    private RegionPrecacheBuilder precacheBuilder;
    private UnloadedChunkCoverageBuilder coverageBuilder;
    private RegionIOThread ioThread;
    private SeasonCacheSyncManager syncManager;

    // Epoch-change detection. Initialised to 0 — first tick populates it without
    // triggering a false transition (0 is not a valid epoch hash in practice).
    private int lastKnownEpoch = 0;

    // Neighbourhood refresh counter.
    private int neighbourhoodTick = 0;

    // Tracks all currently loaded chunk positions per dimension so onSeasonChanged can
    // re-enqueue them. Already-loaded chunks will not re-fire ServerChunkEvents.CHUNK_LOAD
    // after a season transition, so without this set they would retain stale snow/ice
    // indefinitely until unloaded and reloaded by natural player movement.
    // Populated by onChunkLoad, cleared by onChunkUnload.
    private final Map<RegistryKey<World>, Set<Long>> loadedChunkKeys = new HashMap<>();

    // Outgoing snowy chunk set captured at season transition. Populated once from
    // the full in-memory store sweep before flushAll runs, held independently of
    // the store so it isn't affected by epoch eviction or the coverage re-derive.
    // Consulted by onChunkLoad for chunks that were unloaded at transition time.
    // Cleared at the next season transition.
    private final Set<ChunkPos> pendingRemovalChunks = new HashSet<>();

    public static SeasonCacheMod get() { return instance; }

    public SeasonCacheConfig config() { return this.config; }
    public SeasonProvider seasonProvider() { return this.seasonProvider; }
    public SeasonEpochService epochService() { return this.epochService; }
    public ChunkSeasonStore store() { return this.store; }
    public ChunkSeasonQueue reconcileQueue() { return this.reconcileQueue; }
    public RegionPrecacheBuilder precacheBuilder() { return this.precacheBuilder; }
    public UnloadedChunkCoverageBuilder coverageBuilder() { return this.coverageBuilder; }
    public RegionIOThread ioThread() { return this.ioThread; }
    public SeasonCacheSyncManager syncManager() { return this.syncManager; }

    @Override
    public void onInitialize() {
        instance = this;

        this.config          = SeasonCacheConfig.load();
        this.seasonProvider  = new SereneAwareSeasonProvider();
        this.epochService    = new SeasonEpochService(this.config, this.seasonProvider);
        this.ioThread        = new RegionIOThread();
        this.store           = new ChunkSeasonStore();
        this.store.setIOThread(this.ioThread);
        this.syncManager     = new SeasonCacheSyncManager(this.store);
        this.reconciler      = new ChunkSeasonReconciler(this.config, this.seasonProvider, this.epochService, this.store);
        this.reconcileQueue  = new ChunkSeasonQueue(this.config, this.reconciler);
        this.precacheBuilder = new RegionPrecacheBuilder(this.config, this.store);
        this.coverageBuilder = new UnloadedChunkCoverageBuilder(this.config, this.seasonProvider, this.epochService, this.store, this.ioThread);

        SeasonCacheNetworking.registerPayloadTypes();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                SeasonCacheCommands.register(dispatcher));

        ServerChunkEvents.CHUNK_LOAD.register(this::onChunkLoad);
        ServerChunkEvents.CHUNK_UNLOAD.register(this::onChunkUnload);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> this.onPlayerJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> this.syncManager.removePlayer(handler.getPlayer()));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(this::onPlayerChangeWorld);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            this.ioThread.start();
            LOGGER.info("Season Cache IO thread started.");

            // Build the SS-derived seasonal override set once after all biomes and
            // SS state are fully initialised. This identifies biomes like Snowy Taiga
            // that are below the vanilla perennial-cold temperature threshold but are
            // treated seasonally by SS. The result is cached for the session and used
            // in classifyColumn() with O(1) lookup — no per-tick SS calls.
            ServerWorld overworld = server.getOverworld();
            if (overworld != null) {
                var allBiomes = server.getRegistryManager()
                              .get(RegistryKeys.BIOME)
                              .streamEntries()
                              .toList();
                Set<Identifier> overrides =
                        this.seasonProvider.buildSeasonalOverrideSet(overworld, allBiomes);
                this.coverageBuilder.start(overworld, RuntimeTypes.BudgetProfile.PRECACHE);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!this.config.enabled) return;

            this.reconcileQueue.tick(server);

            ServerWorld overworld = server.getOverworld();
            if (overworld == null) return;

            this.precacheBuilder.tick(overworld);
            this.coverageBuilder.tick(overworld);
            this.syncManager.tick(server);

            // Refresh player neighbourhood periodically.
            this.neighbourhoodTick++;
            if (this.neighbourhoodTick >= RegionIOThread.NEIGHBOURHOOD_REFRESH_TICKS) {
                this.neighbourhoodTick = 0;
                refreshPlayerNeighbourhood(server);
            }

            // Season-change detection. Fires within 1 tick of an SS sub-season
            // transition. Equivalent in latency to a GlitchCore event listener
            // without requiring GlitchCore as a compile dependency.
            int currentEpoch = this.epochService.currentEpoch(overworld);
            if (this.lastKnownEpoch == 0) {
                this.lastKnownEpoch = currentEpoch;
            } else if (currentEpoch != this.lastKnownEpoch) {
                String newSeasonKey = this.seasonProvider.snapshot(overworld).seasonKey();
                onSeasonChanged(server, newSeasonKey, currentEpoch);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // Submit final flush before shutting down the IO thread so all
            // in-memory data is persisted. ioThread.shutdown() blocks until
            // the thread finishes draining, guaranteeing writes complete.
            this.store.flushAll();
            this.coverageBuilder.shutdown();
            this.ioThread.shutdown();
            LOGGER.info("Season Cache IO thread shut down.");
        });

        LOGGER.info("Season Cache initialized. provider={}", this.seasonProvider.getProviderId());
    }

    private void onChunkLoad(ServerWorld world, WorldChunk chunk) {
        if (!this.config.enabled) return;
        if (this.config.overworldOnly && world.getRegistryKey() != World.OVERWORLD) return;

        ChunkPos chunkPos = chunk.getPos();

        this.loadedChunkKeys
                .computeIfAbsent(world.getRegistryKey(), k -> new HashSet<>())
                .add(chunkPos.toLong());

        int currentEpoch = this.epochService.currentEpoch(world);

        // If this chunk was snowy in the outgoing epoch, route directly to the
        // removal path. The set is populated from the in-memory store snapshot
        // at transition time — no store read needed here.
        if (this.pendingRemovalChunks.contains(chunkPos)) {
            this.reconcileQueue.enqueueRemoval(world.getRegistryKey(), chunkPos);
            return;
        }

        if (!this.store.isChunkClean(world, chunkPos, currentEpoch)) {
            this.reconcileQueue.enqueue(world.getRegistryKey(), chunkPos);
        }
    }

    private void onChunkUnload(ServerWorld world, WorldChunk chunk) {
        if (this.config.overworldOnly && world.getRegistryKey() != World.OVERWORLD) return;
        Set<Long> set = this.loadedChunkKeys.get(world.getRegistryKey());
        if (set != null) set.remove(chunk.getPos().toLong());
    }

    private void onPlayerJoin(ServerPlayerEntity player) {
        if (!this.config.enabled) return;
        if (player.getWorld().getRegistryKey() != World.OVERWORLD) return;

        ChunkPos origin = player.getChunkPos();
        if (this.coverageBuilder.isActive()) {
            this.coverageBuilder.prioritizeFromPlayer(origin);
        }

        int currentEpoch = this.epochService.currentEpoch((ServerWorld) player.getWorld());
        this.syncManager.scheduleInitialSnapshot(player, currentEpoch);
    }

    private void onPlayerChangeWorld(ServerPlayerEntity player, ServerWorld origin, ServerWorld destination) {
        if (!this.config.enabled) return;
        if (destination.getRegistryKey() != World.OVERWORLD) return;

        ChunkPos playerChunk = player.getChunkPos();
        if (this.coverageBuilder.isActive()) {
            this.coverageBuilder.prioritizeFromPlayer(playerChunk);
        }

        int currentEpoch = this.epochService.currentEpoch(destination);
        this.syncManager.scheduleInitialSnapshot(player, currentEpoch);
    }

    public void onChunkAuthoritativelyReconciled(ServerWorld world, ChunkPos chunkPos, int currentEpoch) {
        Boolean snowy = this.store.getAuthoritativeChunkSnowState(world, chunkPos, currentEpoch);
        if (snowy == null) return;
        this.syncManager.queueChunkStateUpdate(world, chunkPos, currentEpoch, snowy);
    }

    public void onChunkCoverageComputed(ServerWorld world, ChunkPos chunkPos, int currentEpoch, boolean snowy) {
        // If this chunk has already been reconciled and marked clean for the current
        // epoch, the reconciler's confirmed state takes priority. Skipping here
        // prevents the coverage re-derive from overriding a confirmed removal with
        // a stale snowy=true value, which was causing LOD shader decoupling.
        if (this.store.isChunkClean(world, chunkPos, currentEpoch)) return;

        Boolean exact = this.store.getChunkSnowState(world, chunkPos, currentEpoch);
        this.syncManager.queueChunkStateUpdate(world, chunkPos, currentEpoch, exact != null ? exact : snowy);
    }

    /**
     * Refreshes the player neighbourhood set passed to the IO thread.
     *
     * For each online player, computes their current region coordinate and adds
     * all 8 adjacent regions (plus the region they're in) to the set. The IO
     * thread uses this to promote load tasks for nearby-but-not-yet-loaded
     * regions to MEDIUM priority over cold distant regions.
     *
     * Called every NEIGHBOURHOOD_REFRESH_TICKS ticks — 1 second at default (20).
     * Region coordinates change only when a player crosses a 512-block boundary,
     * so per-second refresh is more than sufficient.
     */
    private void refreshPlayerNeighbourhood(MinecraftServer server) {
        Set<String> neighbourhood = new HashSet<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getWorld().getRegistryKey() != World.OVERWORLD) continue;
            int regionX = Math.floorDiv((int) player.getX() >> 4, 32);
            int regionZ = Math.floorDiv((int) player.getZ() >> 4, 32);
            String dim = World.OVERWORLD.getValue().toString();

            // The player's region and all 8 neighbours.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    neighbourhood.add(dim + ":" + (regionX + dx) + ":" + (regionZ + dz));
                }
            }
        }
        this.ioThread.updatePlayerNeighbourhood(neighbourhood);
    }

    /**
     * Called when a season transition is detected on the tick thread.
     *
     * Sequence:
     *   1. Update lastKnownEpoch — prevents re-firing next tick.
     *   2. Flush all in-memory sidecar data via the IO thread (async writes).
     *   3. Clear the reconcile queue — entries were for the old epoch.
     *   4. Re-enqueue all currently loaded chunks. These chunks are already in
     *      memory and will not re-fire onChunkLoad, so without this step they
     *      would retain stale snow/ice until unloaded and reloaded naturally.
     *      isChunkClean is called per-chunk so fully-clean regions still skip.
     *   5. Log the transition with a re-enqueue count.
     */
    private void onSeasonChanged(MinecraftServer server, String newSeasonKey, int newEpoch) {
        int outgoingEpoch = this.lastKnownEpoch;
        this.lastKnownEpoch = newEpoch;

        // Sweep ALL in-memory coverage data for the outgoing epoch BEFORE flushAll
        // evicts anything. This captures every chunk the store knows about that was
        // snowy — not just currently-loaded chunks — into a plain set that lives
        // independently of the store for the duration of the removal pass.
        this.pendingRemovalChunks.clear();
        ServerWorld overworld = server.getOverworld();
        if (overworld != null) {
            this.store.collectSnowyChunks(overworld, outgoingEpoch, this.pendingRemovalChunks);
        }

        this.store.flushAll();
        this.reconcileQueue.clear();
        this.syncManager.broadcastEpochInvalidate(server, World.OVERWORLD, newEpoch);

        if (overworld != null) {
            // Find player origin for distance sort.
            ChunkPos origin = new ChunkPos(overworld.getSpawnPos());
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player.getWorld().getRegistryKey() == World.OVERWORLD) {
                    origin = new ChunkPos(player.getBlockPos());
                    break;
                }
            }
            final ChunkPos sortOrigin = origin;

            // Only enqueue chunks that are currently loaded — unloaded ones will be
            // caught by onChunkLoad when they next load via pendingRemovalChunks.
            Set<Long> overworldLoaded = this.loadedChunkKeys.getOrDefault(World.OVERWORLD, Set.of());
            List<ChunkPos> removalList = new ArrayList<>();
            for (long posLong : overworldLoaded) {
                ChunkPos chunkPos = new ChunkPos(posLong);
                if (this.pendingRemovalChunks.contains(chunkPos)) {
                    removalList.add(chunkPos);
                }
            }

            // Sort nearest-first. Iterate in reverse when inserting at front of deque
            // so the nearest chunk ends up at the actual front after all insertions.
            removalList.sort(Comparator.comparingInt(cp ->
                    Math.max(Math.abs(cp.x - sortOrigin.x), Math.abs(cp.z - sortOrigin.z))));

            for (int i = removalList.size() - 1; i >= 0; i--) {
                this.reconcileQueue.enqueueRemoval(World.OVERWORLD, removalList.get(i));
            }

            LOGGER.info("Season Cache: season transition detected → {}. " +
                    "{} total snowy chunks captured, {} loaded chunks queued for removal.",
                    newSeasonKey, this.pendingRemovalChunks.size(), removalList.size());
        }

        if (overworld != null && this.seasonProvider.requiresCoverageRederive(newSeasonKey)) {
            this.coverageBuilder.start(overworld, this.config.gameplayBudget);
        } else if (overworld != null) {
            LOGGER.info("Season Cache: season transition → {} is a stable sub-season, " +
                    "skipping coverage re-derive (no biomes crossing snow threshold).", newSeasonKey);
        }

        // Re-enqueue remaining loaded chunks not already handled by the removal path.
        int requeued = 0;
        for (Map.Entry<RegistryKey<World>, Set<Long>> entry : this.loadedChunkKeys.entrySet()) {
            RegistryKey<World> dimKey = entry.getKey();
            ServerWorld world = server.getWorld(dimKey);
            if (world == null) continue;
            for (long posLong : entry.getValue()) {
                ChunkPos chunkPos = new ChunkPos(posLong);
                if (!this.store.isChunkClean(world, chunkPos, newEpoch)) {
                    this.reconcileQueue.enqueue(dimKey, chunkPos);
                    requeued++;
                }
            }
        }

        if (requeued > 0) {
            LOGGER.info("Season Cache: {} additional loaded chunks re-enqueued for full reconciliation.",
                    requeued);
        }
    }
}
