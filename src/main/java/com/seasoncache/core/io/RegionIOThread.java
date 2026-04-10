package com.seasoncache.core.io;

import com.seasoncache.SeasonCacheMod;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single background thread that handles all sidecar disk IO.
 *
 * The tick thread never blocks on disk operations. Instead it submits tasks here
 * and reads from the in-memory cache. The IO thread drains the queue in priority
 * order, lowest index = highest priority.
 *
 * Priority tiers:
 *   HIGH   (0) — region load is blocking active reconciliation (chunk already queued)
 *   MEDIUM (1) — region is adjacent to a current player position (8-neighbour set)
 *   LOW    (2) — everything else, including the invalidateAll disk walk
 *
 * Priority is assigned at submission time and is not updated once queued.
 * This is a deliberate tradeoff — reprioritisation would require replacing
 * PriorityBlockingQueue with a more complex structure for minimal practical gain.
 *
 * Player neighbourhood (used for MEDIUM priority) is updated by the tick thread
 * every NEIGHBOURHOOD_REFRESH_TICKS ticks and read by this class at submission time.
 */
public final class RegionIOThread {
    public static final int PRIORITY_HIGH   = 0;
    public static final int PRIORITY_MEDIUM = 1;
    public static final int PRIORITY_LOW    = 2;

    /** How often the tick thread refreshes the player neighbourhood set (ticks). */
    public static final int NEIGHBOURHOOD_REFRESH_TICKS = 20;

    private final PriorityBlockingQueue<IOTask> queue = new PriorityBlockingQueue<>();
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Pending load keys — region keys submitted but not yet completed.
    // Used by the store to avoid submitting duplicate load tasks.
    private final Set<String> pendingLoads = ConcurrentHashMap.newKeySet();

    // Current player neighbourhood — region keys of all regions adjacent to any
    // online player. Written by tick thread via volatile reference swap, so the
    // IO thread always sees a consistent snapshot without locking.
    private volatile Set<String> playerNeighbourhood = Collections.emptySet();

    // Invalidation progress — incremented by the disk walk task as each file is processed.
    // -1 means no invalidation is currently in progress.
    private final AtomicInteger invalidationProcessed = new AtomicInteger(-1);
    private volatile int invalidationTotal = 0;

    public RegionIOThread() {
        this.thread = new Thread(this::run, "season-cache-io");
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

    // -------------------------------------------------------------------------
    // Task submission
    // -------------------------------------------------------------------------

    /**
     * Submits a region load task. No-ops if a load for this key is already pending.
     *
     * @param regionKey   string key identifying the region, used for dedup
     * @param isBlocking  whether active reconciliation is waiting on this region
     * @param task        the disk-read work to perform on the IO thread
     */
    public void submitLoad(String regionKey, boolean isBlocking, Runnable task) {
        if (!this.pendingLoads.add(regionKey)) {
            return; // already queued — skip duplicate
        }
        boolean isNeighbour = this.playerNeighbourhood.contains(regionKey);
        int priority = isBlocking ? PRIORITY_HIGH : isNeighbour ? PRIORITY_MEDIUM : PRIORITY_LOW;
        this.queue.offer(new IOTask(priority, this.taskSeq.getAndIncrement(), () -> {
            try {
                task.run();
            } finally {
                this.pendingLoads.remove(regionKey);
            }
        }));
    }

    /**
     * Submits a region write task. Always LOW priority — nothing on the tick
     * thread is blocked waiting for a write to complete.
     */
    public void submitWrite(Runnable task) {
        this.queue.offer(new IOTask(PRIORITY_LOW, this.taskSeq.getAndIncrement(), task));
    }

    /**
     * Submits a bulk heightmap read task at MEDIUM priority.
     *
     * <p>Heightmap reads are background work for {@link com.seasoncache.core.UnloadedChunkCoverageBuilder}
     * — they should run ahead of LOW-priority writes and invalidation walks so the
     * coverage build pipeline makes steady progress, but should not preempt HIGH-priority
     * loads that are blocking active reconciliation.
     *
     * <p>At server startup (no players online) nothing else is competing at MEDIUM or HIGH,
     * so reads proceed at full IO-thread throughput regardless.
     */
    public void submitHeightmapRead(Runnable task) {
        this.queue.offer(new IOTask(PRIORITY_MEDIUM, this.taskSeq.getAndIncrement(), task));
    }

    /**
     * Submits the invalidateAll on-disk walk as a LOW priority task and sets
     * up progress tracking. The walk task is expected to call
     * {@link #incrementInvalidationProgress()} for each file it processes.
     */
    public void submitInvalidationWalk(int totalFiles, Runnable task) {
        this.invalidationTotal = totalFiles;
        this.invalidationProcessed.set(0);
        this.queue.offer(new IOTask(PRIORITY_LOW, this.taskSeq.getAndIncrement(), () -> {
            try {
                task.run();
            } finally {
                // Ensure processed reaches total even if the walk ends early.
                this.invalidationProcessed.set(totalFiles);
            }
        }));
    }

    /** Called by the invalidation walk task for each file it processes. */
    public void incrementInvalidationProgress() {
        this.invalidationProcessed.incrementAndGet();
    }

    // -------------------------------------------------------------------------
    // Status / queries (called from tick or command threads)
    // -------------------------------------------------------------------------

    public boolean isPendingLoad(String regionKey) {
        return this.pendingLoads.contains(regionKey);
    }

    /**
     * Returns "X/Y" if an invalidation disk walk is in progress, or null if not.
     * Safe to call from any thread.
     */
    public String invalidationProgress() {
        int processed = this.invalidationProcessed.get();
        if (processed < 0) return null;
        return processed + "/" + this.invalidationTotal;
    }

    /** Returns true when the most recent invalidation walk has fully completed. */
    public boolean isInvalidationComplete() {
        int processed = this.invalidationProcessed.get();
        return processed >= 0 && processed >= this.invalidationTotal;
    }

    // -------------------------------------------------------------------------
    // Player neighbourhood (written by tick thread, read at submission time)
    // -------------------------------------------------------------------------

    /**
     * Replaces the current player neighbourhood set.
     * Called by the tick thread every NEIGHBOURHOOD_REFRESH_TICKS ticks.
     * The volatile reference swap is safe without locking — the IO thread reads
     * a consistent snapshot, just potentially one refresh cycle stale.
     */
    public void updatePlayerNeighbourhood(Set<String> neighbourhood) {
        this.playerNeighbourhood = neighbourhood;
    }

    // -------------------------------------------------------------------------
    // Thread loop
    // -------------------------------------------------------------------------

    private void run() {
        while (this.running.get()) {
            try {
                IOTask task = this.queue.take(); // blocks until work is available
                task.work().run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!this.running.get()) break;
            } catch (Exception e) {
                SeasonCacheMod.LOGGER.error(
                    "Season Cache IO thread: unhandled exception in task.", e);
            }
        }

        // Drain remaining tasks on shutdown so in-flight writes are not silently lost.
        IOTask task;
        while ((task = this.queue.poll()) != null) {
            try {
                task.work().run();
            } catch (Exception e) {
                SeasonCacheMod.LOGGER.error(
                    "Season Cache IO thread: unhandled exception during shutdown drain.", e);
            }
        }
    }

    // Monotonically increasing counter assigned to every submitted task.
    // Used as a tiebreaker in IOTask.compareTo so that tasks with equal priority
    // drain in strict FIFO (submission) order. Without this, PriorityBlockingQueue
    // makes no ordering guarantee for equal-priority elements — the heap can return
    // them in any order, silently discarding the nearest-first submission ordering
    // produced by UnloadedChunkCoverageBuilder.prioritizeFromPlayer.
    private final AtomicLong taskSeq = new AtomicLong(0);

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    private record IOTask(int priority, long seqNo, Runnable work) implements Comparable<IOTask> {
        @Override
        public int compareTo(IOTask other) {
            int cmp = Integer.compare(this.priority, other.priority);
            if (cmp != 0) return cmp;
            return Long.compare(this.seqNo, other.seqNo); // FIFO within same priority
        }
    }
}
