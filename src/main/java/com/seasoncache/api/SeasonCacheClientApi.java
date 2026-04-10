package com.seasoncache.api;

import com.seasoncache.client.SeasonCacheClientState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.List;

/**
 * Public client bridge for other client mods (such as Nova Reimagined Snow).
 *
 * This API is deliberately narrow:
 *  - query whether server-authoritative snow coverage is active for a dimension
 *  - query the latest known chunk snow state
 *  - drain queued client events so consumers can update their own textures/caches
 */
public final class SeasonCacheClientApi {
    private SeasonCacheClientApi() {
    }

    public static boolean isAuthoritativeSessionActive() {
        return SeasonCacheClientState.isAuthoritativeSessionActive();
    }

    public static boolean isSnapshotInProgress(RegistryKey<World> dimension) {
        return SeasonCacheClientState.isSnapshotInProgress(dimension);
    }

    public static Integer currentEpoch(RegistryKey<World> dimension) {
        return SeasonCacheClientState.currentEpoch(dimension);
    }

    /**
     * @return Boolean.TRUE / Boolean.FALSE when authoritative data exists for the chunk,
     *         or null when the server has not provided a value for it yet.
     */
    public static Boolean getChunkSnowState(RegistryKey<World> dimension, int chunkX, int chunkZ) {
        return SeasonCacheClientState.getChunkSnowState(dimension, chunkX, chunkZ);
    }

    /**
     * Drains queued reset/update events accumulated from the Season Cache sync stream.
     * Consumers should drain this once per client tick and update their own textures/caches.
     */
    public static List<ClientSnowEvent> drainEvents() {
        return SeasonCacheClientState.drainEvents();
    }

    public enum ClientSnowEventType {
        RESET,
        CHUNK_STATE
    }

    public record ClientSnowEvent(
            ClientSnowEventType type,
            RegistryKey<World> dimension,
            int epoch,
            int chunkX,
            int chunkZ,
            boolean snowy
    ) {
        public static ClientSnowEvent reset(RegistryKey<World> dimension, int epoch) {
            return new ClientSnowEvent(ClientSnowEventType.RESET, dimension, epoch, 0, 0, false);
        }

        public static ClientSnowEvent chunkState(RegistryKey<World> dimension, int epoch, int chunkX, int chunkZ, boolean snowy) {
            return new ClientSnowEvent(ClientSnowEventType.CHUNK_STATE, dimension, epoch, chunkX, chunkZ, snowy);
        }
    }
}
