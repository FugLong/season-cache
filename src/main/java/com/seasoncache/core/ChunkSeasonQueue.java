package com.seasoncache.core;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.seasoncache.config.SeasonCacheConfig;

public final class ChunkSeasonQueue {
    private final SeasonCacheConfig config;
    private final ChunkSeasonReconciler reconciler;
    private final ArrayDeque<QueueEntry> queue = new ArrayDeque<>();
    private final Set<QueueEntry> queued = new HashSet<>();

    public ChunkSeasonQueue(SeasonCacheConfig config, ChunkSeasonReconciler reconciler) {
        this.config = config;
        this.reconciler = reconciler;
    }

    public void enqueue(RegistryKey<World> dimension, ChunkPos chunkPos) {
        QueueEntry entry = new QueueEntry(dimension, chunkPos, false);
        if (this.queued.add(entry)) {
            this.queue.addLast(entry);
        }
    }

    /**
     * Enqueues a chunk for removal-only reconciliation, bypassing the proximity
     * gate. Used on season transition where the outgoing epoch's snowy=true set
     * is the authoritative removal list — no decision-making needed, just clear.
     * Sorted by player distance before enqueueing so nearest chunks clear first.
     */
    public void enqueueRemoval(RegistryKey<World> dimension, ChunkPos chunkPos) {
        QueueEntry entry = new QueueEntry(dimension, chunkPos, true);
        if (this.queued.add(entry)) {
            this.queue.addFirst(entry); // removal entries go to the front
        }
    }

    public int size() {
        return this.queue.size();
    }

    public void clear() {
        this.queue.clear();
        this.queued.clear();
    }

    /**
     * Processes queued chunks subject to the configured budget.
     *
     * Proximity gate: if proximityGateChunks > 0, a chunk is deferred to the back
     * of the queue when no online player is within that many chunks of it (Chebyshev
     * distance). This keeps the reconcile budget focused on terrain near active
     * players rather than distant chunks loaded during fast flight.
     *
     * Starvation guard: a chunk that has waited longer than maxChunkDeferMs is
     * processed unconditionally regardless of proximity. This ensures every queued
     * chunk is eventually handled even if no player remains nearby.
     *
     * Deferral cap: at most chunksPerTick * 8 deferral checks are performed per tick.
     * Once exhausted, subsequent chunks are processed regardless of proximity. This
     * prevents the tick from spinning through a large all-distant queue.
     */
    public void tick(MinecraftServer server) {
        RuntimeTypes.Budget budget = this.config.budgetFor(this.config.gameplayBudget);
        long start = System.nanoTime();
        int processed = 0;
        // How many proximity-gate deferral checks we allow this tick before
        // falling back to unconditional processing.
        int defersRemaining = this.config.proximityGateChunks > 0
                ? budget.chunksPerTick() * 8
                : 0;

        while (processed < budget.chunksPerTick() && !this.queue.isEmpty()) {
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            if (elapsedMillis >= budget.maxMillisPerTick()) break;

            QueueEntry entry = this.queue.removeFirst();
            this.queued.remove(entry);

            // Proximity gate — defer if no player is nearby and time cap not exceeded.
            // Removal entries bypass this gate entirely — the work is predetermined
            // and should clear as fast as the budget allows.
            if (defersRemaining > 0 && !entry.removeOnly) {
                long waitedMs = System.currentTimeMillis() - entry.enqueuedAtMs;
                if (waitedMs < this.config.maxChunkDeferMs
                        && !isNearAnyPlayer(server, entry)) {
                    this.queue.addLast(entry);
                    this.queued.add(entry);
                    defersRemaining--;
                    continue;
                }
            }

            ServerWorld world = server.getWorld(entry.dimension);
            if (world != null && world.isChunkLoaded(entry.chunkPos.x, entry.chunkPos.z)) {
                if (entry.removeOnly) {
                    this.reconciler.reconcileRemoveOnly(world, entry.chunkPos);
                } else {
                    this.reconciler.reconcile(world, entry.chunkPos);
                }
            }

            processed++;
        }
    }

    /**
     * Returns true if any online player is within proximityGateChunks of the given
     * queue entry (same dimension, Chebyshev distance).
     */
    private boolean isNearAnyPlayer(MinecraftServer server, QueueEntry entry) {
        int threshold = this.config.proximityGateChunks;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getWorld().getRegistryKey() != entry.dimension) continue;
            ChunkPos playerChunk = new ChunkPos(player.getBlockPos());
            if (Math.abs(playerChunk.x - entry.chunkPos.x) <= threshold
                    && Math.abs(playerChunk.z - entry.chunkPos.z) <= threshold) {
                return true;
            }
        }
        return false;
    }

    private static final class QueueEntry {
        private final RegistryKey<World> dimension;
        private final ChunkPos chunkPos;
        private final boolean removeOnly;
        // Recorded once at construction. Preserved when entry is deferred and
        // re-added to the back of the queue so the starvation timer is accurate.
        // Not included in equals/hashCode — identity is dimension + chunkPos only.
        private final long enqueuedAtMs;

        private QueueEntry(RegistryKey<World> dimension, ChunkPos chunkPos, boolean removeOnly) {
            this.dimension = dimension;
            this.chunkPos = chunkPos;
            this.removeOnly = removeOnly;
            this.enqueuedAtMs = System.currentTimeMillis();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof QueueEntry other)) return false;
            return Objects.equals(this.dimension, other.dimension)
                && Objects.equals(this.chunkPos, other.chunkPos);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.dimension, this.chunkPos);
        }
    }
}
