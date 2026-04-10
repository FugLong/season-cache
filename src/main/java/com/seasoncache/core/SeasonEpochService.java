package com.seasoncache.core;

import com.seasoncache.config.SeasonCacheConfig;
import com.seasoncache.integration.SeasonProvider;
import net.minecraft.server.world.ServerWorld;

import java.util.Objects;

public final class SeasonEpochService {
    /**
     * Increment this value whenever any logic change affects what the climate bits
     * stored in ChunkSeasonStore represent — i.e. whenever shouldHaveSnowNow or
     * shouldHaveIceNow would return a different result for the same inputs compared
     * to a previous version of the mod.
     *
     * This integer is included in the epoch hash via Objects.hash(), so bumping it
     * invalidates every stored climate bit across every sidecar file globally.
     * Chunks will recompute fresh decisions on their next reconcile pass without
     * any manual invalidation or data migration.
     *
     * History:
     *   1 — initial release (v1.0.0)
     *   2 — v1.1.0: hysteresis ramp, surfacePos temperature sampling,
     *               non-winter short-circuit removed
     *   3 — v1.2.0: perennial cold columns now correctly record snowBit=1 in climate
     *               bits when neverTouchPerennialColumns=true. Previous caches stored
     *               all-zero snowBits for these columns, causing reconciler deltas to
     *               override correct coverage data with snowy=false for biomes like
     *               jagged_peaks, frozen_peaks, snowy_slopes, frozen_ocean, etc.
     */
    public static final int SCHEMA_VERSION = 3;

    private final SeasonCacheConfig config;
    private final SeasonProvider seasonProvider;

    public SeasonEpochService(SeasonCacheConfig config, SeasonProvider seasonProvider) {
        this.config = config;
        this.seasonProvider = seasonProvider;
    }

    /**
     * Returns an integer epoch representing the current season/config state.
     * A chunk is considered stale when its stored epoch differs from this value.
     *
     * The epoch changes when:
     *   - Serene Seasons transitions to a new sub-season (different seasonKey)
     *   - Any config value that affects what a "correct" chunk looks like changes
     *     (tracked via epochConfigHash)
     *   - The sidecar schema version changes
     *
     * Implementation note: Objects.hash produces a 32-bit value, so two distinct
     * season/config combinations could theoretically collide and produce the same
     * epoch — causing a stale chunk to be treated as current. Given the small input
     * space this probability is negligible in practice. A future improvement could
     * use a monotonic counter incremented on the SS season change event instead,
     * which would eliminate the collision risk entirely.
     */
    public int currentEpoch(ServerWorld world) {
        RuntimeTypes.SeasonSnapshot snapshot = this.seasonProvider.snapshot(world);
        return Objects.hash(
                snapshot.providerId(),
                snapshot.seasonKey(),
                this.config.epochConfigHash(),
                SCHEMA_VERSION
        );
    }
}
