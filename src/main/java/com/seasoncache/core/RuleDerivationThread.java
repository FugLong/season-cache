package com.seasoncache.core;

import com.seasoncache.SeasonCacheMod;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated background thread for per-chunk season rule derivation.
 *
 * The rule derivation computation — iterating over all 12 SS sub-seasons and
 * calling getBiomeTemperatureInSeason for each — is pure arithmetic on immutable
 * data (biome entry, block position, season config). It carries no world state
 * and is safe to run off the main thread.
 *
 * The main thread is responsible for resolving world-dependent inputs
 * (surface height, biome entry) before submitting a DerivationTask. Completed
 * rules are posted to a ConcurrentLinkedQueue for the main thread to pick up
 * and write to the store on its next tick.
 *
 * Fallback chain if thread creation fails (OOM):
 *   1. Dedicated derivation thread  ← primary
 *   2. Shared IO thread              ← if thread allocation fails
 *   3. Inline on main thread         ← extreme fallback, never expected in practice
 *
 * SeasonCacheMod selects the appropriate path at startup and signals which
 * was chosen via LOGGER. The rest of the mod submits tasks without knowing
 * which executor was selected.
 */
public final class RuleDerivationThread {
    private final BlockingQueue<DerivationTask> queue = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<DerivationResult> results;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger pendingCount = new AtomicInteger(0);

    public RuleDerivationThread(ConcurrentLinkedQueue<DerivationResult> results) {
        this.results = results;
        this.thread = new Thread(this::run, "season-cache-derive");
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.NORM_PRIORITY - 1);
    }

    public void start() {
        this.thread.start();
    }

    public void shutdown() {
        this.running.set(false);
        this.thread.interrupt();
        try {
            this.thread.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void submit(DerivationTask task) {
        this.pendingCount.incrementAndGet();
        this.queue.offer(task);
    }

    /** Returns the number of tasks currently queued or in progress. */
    public int pendingCount() {
        return this.pendingCount.get();
    }

    private void run() {
        while (this.running.get()) {
            try {
                DerivationTask task = this.queue.take();
                try {
                    RuntimeTypes.ChunkSeasonRule rule = ChunkSeasonReconciler.buildChunkSeasonRule(
                            task.samplePos(), task.biomeEntry(), task.ruleConfig());
                    this.results.offer(new DerivationResult(task.chunkPos(), rule, task.staticSample()));
                } finally {
                    this.pendingCount.decrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!this.running.get()) break;
            } catch (Exception e) {
                SeasonCacheMod.LOGGER.error(
                        "Season Cache derivation thread: unhandled exception.", e);
                this.pendingCount.decrementAndGet();
            }
        }

        // Drain remaining tasks on shutdown so results aren't silently lost.
        DerivationTask task;
        while ((task = this.queue.poll()) != null) {
            try {
                RuntimeTypes.ChunkSeasonRule rule = ChunkSeasonReconciler.buildChunkSeasonRule(
                        task.samplePos(), task.biomeEntry(), task.ruleConfig());
                this.results.offer(new DerivationResult(task.chunkPos(), rule, task.staticSample()));
            } catch (Exception e) {
                SeasonCacheMod.LOGGER.error(
                        "Season Cache derivation thread: unhandled exception during shutdown drain.", e);
            } finally {
                this.pendingCount.decrementAndGet();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Data types
    // -------------------------------------------------------------------------

    /**
     * Inputs to a single-chunk rule derivation task.
     * All fields are immutable and safe to read from the derivation thread.
     * World-dependent values (samplePos, biomeEntry, staticSample) must be
     * resolved on the main thread before submission.
     */
    public record DerivationTask(
            ChunkPos chunkPos,
            BlockPos samplePos,
            RegistryEntry<Biome> biomeEntry,
            RuntimeTypes.SeasonRuleConfig ruleConfig,
            RuntimeTypes.StaticChunkClimate staticSample
    ) {}

    /**
     * Result of a completed derivation. Posted to the shared results queue
     * and consumed by the main thread in its next tick drain pass.
     */
    public record DerivationResult(
            ChunkPos chunkPos,
            RuntimeTypes.ChunkSeasonRule rule,
            RuntimeTypes.StaticChunkClimate staticSample
    ) {}
}
