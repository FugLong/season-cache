package com.seasoncache.server;

import com.seasoncache.SeasonCacheMod;
import com.seasoncache.core.store.ChunkSeasonStore;
import com.seasoncache.network.ChunkStatePacking;
import com.seasoncache.network.payload.ChunkStatesPayload;
import com.seasoncache.network.payload.EpochInvalidatePayload;
import com.seasoncache.network.payload.SnapshotBeginPayload;
import com.seasoncache.network.payload.SnapshotEndPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side authoritative chunk snow sync.
 *
 * Responsibilities:
 *  - send initial snapshots to supported clients on join and overworld re-entry
 *  - send epoch invalidation on season transitions
 *  - batch and stream chunk snow deltas after reconciliation completes
 *
 * Transport is intentionally simple: chunk snow is a single boolean sourced from
 * either exact per-column reconcile data or coarse unloaded coverage data.
 */
public final class SeasonCacheSyncManager {
    private static final int MAX_CHUNKS_PER_PACKET = 1024;
    private static final int MAX_CHUNK_PACKETS_PER_TICK = 3;

    private final ChunkSeasonStore store;
    private final Map<UUID, PlayerSyncState> playerStates = new HashMap<>();

    public SeasonCacheSyncManager(ChunkSeasonStore store) {
        this.store = store;
    }

    public void removePlayer(ServerPlayerEntity player) {
        this.playerStates.remove(player.getUuid());
    }

    public void scheduleInitialSnapshot(ServerPlayerEntity player, int currentEpoch) {
        if (player.getWorld().getRegistryKey() != World.OVERWORLD) return;
        if (!ServerPlayNetworking.canSend(player, SnapshotBeginPayload.ID)) return;

        ChunkPos origin = player.getChunkPos();
        PlayerSyncState state = this.playerStates.computeIfAbsent(player.getUuid(), ignored -> new PlayerSyncState());
        state.playerOrigin = origin;
        state.snapshotPending = buildSnapshotEntries((ServerWorld) player.getWorld(), currentEpoch, origin);
        state.snapshotEpoch = currentEpoch;
        state.snapshotRequested = true;
        state.snapshotStarted = false;
        state.snapshotFinished = false;
        state.deltaPending.clear();
    }

    public void broadcastEpochInvalidate(MinecraftServer server, RegistryKey<World> dimension, int newEpoch) {
        ServerWorld world = server.getWorld(dimension);
        if (world == null) return;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getWorld().getRegistryKey() != dimension) continue;
            if (!ServerPlayNetworking.canSend(player, EpochInvalidatePayload.ID)) continue;

            ServerPlayNetworking.send(player, new EpochInvalidatePayload(world.getRegistryKey().getValue(), newEpoch));

            PlayerSyncState state = this.playerStates.computeIfAbsent(player.getUuid(), ignored -> new PlayerSyncState());
            state.snapshotPending.clear();
            state.deltaPending.clear();
            state.snapshotRequested = false;
            state.snapshotStarted = false;
            state.snapshotFinished = false;
            state.snapshotEpoch = newEpoch;
        }
    }

    public void queueChunkStateUpdate(ServerWorld world, ChunkPos chunkPos, int currentEpoch, boolean snowy) {
        long packed = ChunkStatePacking.packChunkState(chunkPos.x, chunkPos.z, snowy);
        for (PlayerSyncState state : this.playerStates.values()) {
            state.deltaPending.addLast(new PendingChunkState(world.getRegistryKey(), currentEpoch, packed));
        }
    }

    public void tick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            PlayerSyncState state = this.playerStates.get(player.getUuid());
            if (state == null) continue;
            if (player.getWorld().getRegistryKey() != World.OVERWORLD) continue;
            if (!ServerPlayNetworking.canSend(player, SnapshotBeginPayload.ID)) continue;

            sendSnapshotBatches(player, state);
            sendDeltaBatches(player, state);
        }
    }

    private void sendSnapshotBatches(ServerPlayerEntity player, PlayerSyncState state) {
        if (state.snapshotRequested) {
            if (!state.snapshotStarted) {
                ServerPlayNetworking.send(player, new SnapshotBeginPayload(World.OVERWORLD.getValue(), state.snapshotEpoch));
                state.snapshotStarted = true;
            }

            int packetsSent = 0;
            while (!state.snapshotPending.isEmpty() && packetsSent < MAX_CHUNK_PACKETS_PER_TICK) {
                long[] batch = drainChunkStateBatch(state.snapshotPending);
                ServerPlayNetworking.send(player, new ChunkStatesPayload(World.OVERWORLD.getValue(), state.snapshotEpoch, batch));
                packetsSent++;
            }

            if (state.snapshotPending.isEmpty() && !state.snapshotFinished) {
                ServerPlayNetworking.send(player, new SnapshotEndPayload(World.OVERWORLD.getValue(), state.snapshotEpoch));
                state.snapshotFinished = true;
                state.snapshotRequested = false;
                sortDeltasByOrigin(state);
            }
        }
    }

    private void sendDeltaBatches(ServerPlayerEntity player, PlayerSyncState state) {
        int packetsSent = 0;
        while (!state.deltaPending.isEmpty() && packetsSent < MAX_CHUNK_PACKETS_PER_TICK) {
            PendingChunkState head = state.deltaPending.peekFirst();
            if (head == null) return;

            if (player.getWorld().getRegistryKey() != head.dimension()) {
                return;
            }

            List<Long> batchValues = new ArrayList<>(MAX_CHUNKS_PER_PACKET);
            int epoch = head.epoch();
            while (!state.deltaPending.isEmpty() && batchValues.size() < MAX_CHUNKS_PER_PACKET) {
                PendingChunkState next = state.deltaPending.peekFirst();
                if (next == null || next.epoch() != epoch || next.dimension() != head.dimension()) {
                    break;
                }
                batchValues.add(next.packedChunkState());
                state.deltaPending.removeFirst();
            }

            long[] packed = new long[batchValues.size()];
            for (int i = 0; i < batchValues.size(); i++) {
                packed[i] = batchValues.get(i);
            }
            ServerPlayNetworking.send(player, new ChunkStatesPayload(head.dimension().getValue(), epoch, packed));
            packetsSent++;
        }
    }

    /**
     * One-time sort of the accumulated delta backlog by distance from the player's
     * fixed login origin. Called once when the initial snapshot finishes sending so
     * that the delta stream arrives nearest-first rather than in builder/region order.
     * New deltas queued after this sort append to the back and are not re-sorted;
     * those are live updates that should be processed promptly regardless of distance.
     */
    private static void sortDeltasByOrigin(PlayerSyncState state) {
        if (state.playerOrigin == null || state.deltaPending.isEmpty()) return;
        List<PendingChunkState> list = new ArrayList<>(state.deltaPending);
        ChunkPos origin = state.playerOrigin;
        list.sort((a, b) -> Long.compare(
                deltaDistanceSq(a.packedChunkState(), origin),
                deltaDistanceSq(b.packedChunkState(), origin)
        ));
        state.deltaPending.clear();
        for (PendingChunkState entry : list) {
            state.deltaPending.addLast(entry);
        }
    }

    private static long deltaDistanceSq(long packed, ChunkPos origin) {
        long dx = (long) ChunkStatePacking.unpackChunkX(packed) - origin.x;
        long dz = (long) ChunkStatePacking.unpackChunkZ(packed) - origin.z;
        return dx * dx + dz * dz;
    }

    private ArrayDeque<Long> buildSnapshotEntries(ServerWorld world, int currentEpoch, ChunkPos origin) {
        ArrayDeque<Long> entries = new ArrayDeque<>();

        List<ChunkSeasonStore.AuthoritativeChunkState> states =
                this.store.snapshotAuthoritativeChunkSnowStates(world, currentEpoch);
        states.sort((a, b) -> Long.compare(distanceSq(a.chunkPos(), origin), distanceSq(b.chunkPos(), origin)));

        for (ChunkSeasonStore.AuthoritativeChunkState state : states) {
            ChunkPos chunkPos = state.chunkPos();
            entries.addLast(ChunkStatePacking.packChunkState(chunkPos.x, chunkPos.z, state.snowy()));
        }

        SeasonCacheMod.LOGGER.info("Season Cache sync: prepared player-prioritized snapshot of {} authoritative chunk states for {} at epoch {} from origin [{}, {}].",
                entries.size(), world.getRegistryKey().getValue(), currentEpoch, origin.x, origin.z);
        return entries;
    }

    private static long distanceSq(ChunkPos pos, ChunkPos origin) {
        long dx = (long) pos.x - origin.x;
        long dz = (long) pos.z - origin.z;
        return dx * dx + dz * dz;
    }

    private static long[] drainChunkStateBatch(ArrayDeque<Long> queue) {
        int size = Math.min(queue.size(), MAX_CHUNKS_PER_PACKET);
        long[] batch = new long[size];
        for (int i = 0; i < size; i++) {
            Long next = queue.pollFirst();
            if (next == null) break;
            batch[i] = next;
        }
        return batch;
    }

    private static final class PlayerSyncState {
        private ArrayDeque<Long> snapshotPending = new ArrayDeque<>();
        private final ArrayDeque<PendingChunkState> deltaPending = new ArrayDeque<>();
        private int snapshotEpoch = 0;
        private boolean snapshotRequested = false;
        private boolean snapshotStarted = false;
        private boolean snapshotFinished = false;
        /** Fixed origin captured at login/overworld-entry. Used to sort the delta backlog once at snapshot completion. */
        private ChunkPos playerOrigin = null;
    }

    private record PendingChunkState(RegistryKey<World> dimension, int epoch, long packedChunkState) {
    }
}
