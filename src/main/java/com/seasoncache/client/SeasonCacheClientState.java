package com.seasoncache.client;

import com.seasoncache.api.SeasonCacheClientApi;
import com.seasoncache.network.ChunkStatePacking;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Client-side authoritative chunk snow state received from the Season Cache server sync stream.
 *
 * Threading:
 *  - Networking callbacks can update this state off the main render thread.
 *  - Data structures are therefore concurrent and Nova (or other consumers) should
 *    consume via the public API on the client tick.
 */
public final class SeasonCacheClientState {
    private static final Map<RegistryKey<World>, DimensionState> DIMENSIONS = new ConcurrentHashMap<>();
    private static final Queue<SeasonCacheClientApi.ClientSnowEvent> EVENTS = new ConcurrentLinkedQueue<>();
    private static volatile boolean authoritativeSessionActive = false;

    private SeasonCacheClientState() {
    }

    public static void resetAll() {
        DIMENSIONS.clear();
        EVENTS.clear();
        authoritativeSessionActive = false;
    }

    public static boolean isAuthoritativeSessionActive() {
        return authoritativeSessionActive;
    }

    public static boolean isSnapshotInProgress(RegistryKey<World> dimension) {
        DimensionState state = DIMENSIONS.get(dimension);
        return state != null && state.snapshotInProgress;
    }

    public static Integer currentEpoch(RegistryKey<World> dimension) {
        DimensionState state = DIMENSIONS.get(dimension);
        return state != null ? state.epoch : null;
    }

    public static Boolean getChunkSnowState(RegistryKey<World> dimension, int chunkX, int chunkZ) {
        DimensionState state = DIMENSIONS.get(dimension);
        if (state == null) return null;
        return state.chunkSnowStates.get(packChunkKey(chunkX, chunkZ));
    }

    public static List<SeasonCacheClientApi.ClientSnowEvent> drainEvents() {
        List<SeasonCacheClientApi.ClientSnowEvent> drained = new ArrayList<>();
        SeasonCacheClientApi.ClientSnowEvent event;
        while ((event = EVENTS.poll()) != null) {
            drained.add(event);
        }
        return drained;
    }

    public static void beginSnapshot(Identifier dimensionId, int epoch) {
        RegistryKey<World> dimension = toWorldKey(dimensionId);
        DimensionState state = DIMENSIONS.computeIfAbsent(dimension, ignored -> new DimensionState());
        state.chunkSnowStates.clear();
        state.epoch = epoch;
        state.snapshotInProgress = true;
        authoritativeSessionActive = true;
        EVENTS.add(SeasonCacheClientApi.ClientSnowEvent.reset(dimension, epoch));
    }

    public static void applyInvalidate(Identifier dimensionId, int epoch) {
        RegistryKey<World> dimension = toWorldKey(dimensionId);
        DimensionState state = DIMENSIONS.computeIfAbsent(dimension, ignored -> new DimensionState());
        state.chunkSnowStates.clear();
        state.epoch = epoch;
        state.snapshotInProgress = false;
        authoritativeSessionActive = true;
        EVENTS.add(SeasonCacheClientApi.ClientSnowEvent.reset(dimension, epoch));
    }

    public static void endSnapshot(Identifier dimensionId, int epoch) {
        RegistryKey<World> dimension = toWorldKey(dimensionId);
        DimensionState state = DIMENSIONS.computeIfAbsent(dimension, ignored -> new DimensionState());
        state.epoch = epoch;
        state.snapshotInProgress = false;
        authoritativeSessionActive = true;
    }

    public static void applyChunkBatch(Identifier dimensionId, int epoch, long[] packedChunkStates) {
        RegistryKey<World> dimension = toWorldKey(dimensionId);
        DimensionState state = DIMENSIONS.computeIfAbsent(dimension, ignored -> new DimensionState());
        state.epoch = epoch;
        authoritativeSessionActive = true;

        for (long packed : packedChunkStates) {
            int chunkX = ChunkStatePacking.unpackChunkX(packed);
            int chunkZ = ChunkStatePacking.unpackChunkZ(packed);
            boolean snowy = ChunkStatePacking.unpackSnowy(packed);
            state.chunkSnowStates.put(packChunkKey(chunkX, chunkZ), snowy);
            EVENTS.add(SeasonCacheClientApi.ClientSnowEvent.chunkState(dimension, epoch, chunkX, chunkZ, snowy));
        }
    }

    private static long packChunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static RegistryKey<World> toWorldKey(Identifier dimensionId) {
        return RegistryKey.of(RegistryKeys.WORLD, Objects.requireNonNull(dimensionId));
    }

    private static final class DimensionState {
        private final Map<Long, Boolean> chunkSnowStates = new ConcurrentHashMap<>();
        private volatile int epoch = 0;
        private volatile boolean snapshotInProgress = false;
    }
}
