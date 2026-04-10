package com.seasoncache.integration;

import com.seasoncache.core.RuntimeTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import java.util.Set;

/**
 * Abstraction over Serene Seasons' climate API.
 *
 * All SS calls are routed through this interface. The rest of the mod never
 * touches SS classes directly, keeping the SS dependency contained in one place.
 *
 * SS is a hard runtime dependency — Fabric Loader enforces this via fabric.mod.json
 * before any of this code runs.
 */
public interface SeasonProvider {
    /** Stable identifier used in epoch hashing and status output. */
    String getProviderId();

    /**
     * Returns a snapshot of the current season state for epoch computation.
     * The snapshot's seasonKey is the SS sub-season serialized name,
     * e.g. "early_spring", "mid_winter", "late_summer".
     */
    RuntimeTypes.SeasonSnapshot snapshot(ServerWorld world);

    /**
     * Builds the set of biome registry keys that are classified as perennially
     * cold by vanilla base temperature (< 0.15f) but are treated seasonally by
     * Serene Seasons. Called once at server start after datapacks and SS are
     * fully initialised.
     *
     * This set is used by {@link #classifyColumn} to correctly promote
     * biomes like Snowy Taiga from PERENNIAL_COLD to SEASONAL_TEMPORARY
     * without any per-tick SS queries.
     *
     * @param world any loaded ServerWorld (used for SS context, not position-specific)
     * @param allBiomes all registered biomes to scan
     */
    Set<Identifier> buildSeasonalOverrideSet(ServerWorld world,
            Iterable<? extends RegistryEntry<Biome>> allBiomes);

    /**
     * Tier 1 biome gate. Fast — no SS climate query.
     * Classifies a column based on biome properties and the pre-built seasonal
     * override set. Called once per sky-exposed column per cold-cache chunk.
     *
     * @param seasonalColdOverrides set built by {@link #buildSeasonalOverrideSet}
     */
    RuntimeTypes.ColumnType classifyColumn(ServerWorld world, BlockPos pos,
            RegistryEntry<Biome> biome, Set<Identifier> seasonalColdOverrides);

    /**
     * Returns true if the current SS sub-season is a winter variant.
     * Called once per chunk reconcile pass, not per column.
     */
    boolean isSnowSeason(ServerWorld world);

    /**
     * Returns true if transitioning into the given sub-season requires the
     * unloaded coverage builder to re-derive snow coverage from static samples.
     *
     * Only the shoulder seasons around winter — where biomes are actively crossing
     * the snow threshold — need a re-derive pass. Stable seasons (deep summer,
     * mid-autumn, late spring, etc.) produce no change in which unloaded chunks
     * have snow coverage, so the existing cached state remains correct.
     *
     * Defaults to true so that unknown or custom providers always re-derive,
     * which is safe even if redundant.
     *
     * @param seasonKey the sub-season serialized name, e.g. "late_autumn"
     */
    default boolean requiresCoverageRederive(String seasonKey) {
        return true;
    }

    /**
     * Captures the minimal seasonal state needed to classify coarse unloaded-chunk
     * snow coverage off the main thread.
     */
    RuntimeTypes.CoverageSeasonSnapshot snapshotCoverageSeason(ServerWorld world);

    /**
     * Captures the minimal biome/sample data needed to classify a coarse unloaded
     * chunk snow decision off the main thread. The caller is responsible for
     * obtaining any world-dependent values (height, biome lookup, temperature at pos)
     * on the server thread before calling this.
     */
    RuntimeTypes.CoverageSample snapshotCoverageSample(ServerWorld world, BlockPos pos,
            RegistryEntry<Biome> biome);

    /**
     * Pure coarse unloaded-chunk snow decision using immutable sampled data and a
     * season snapshot captured on the server thread.
     */
    boolean shouldSampleSnowCoverage(RuntimeTypes.CoverageSeasonSnapshot seasonSnapshot,
            RuntimeTypes.CoverageSample sample);

    /**
     * Tier 2 snow decision. Uses SS's season-aware temperature to compute a
     * deterministic per-column coverage probability within the hysteresis band.
     * Outside the band the decision is authoritative (always/never).
     * Only called on cold-cache chunks; results are cached by the reconciler.
     *
     * In non-winter, biomes with base temperature >= 0.15 short-circuit to false
     * immediately with no SS call — they are unambiguously too warm to snow outside
     * winter and this makes non-winter reconciliation essentially free for the vast
     * majority of terrain. Biomes with base temp < 0.15 (override biomes like
     * snowy_taiga, snowy_plains) still call getBiomeTemperature so SS can return
     * their seasonally-adjusted value — SS warms them in summer (→ false) but may
     * keep them cold in shoulder seasons (→ true based on ramp).
     *
     * @param isSnowSeason true if the current SS sub-season is a winter variant
     * @param epoch        current season epoch, used as part of the noise seed
     * @param bandWidth    hysteresis half-width from config
     */
    boolean shouldHaveSnowNow(ServerWorld world, BlockPos pos,
            RegistryEntry<Biome> biome, boolean isSnowSeason, int epoch, float bandWidth);

    /**
     * Tier 2 ice decision. Mirrors shouldHaveSnowNow including the non-winter
     * fast path. Spatial restriction to shoreline-adjacent cells is enforced by
     * the reconciler, not here.
     */
    boolean shouldHaveIceNow(ServerWorld world, BlockPos pos,
            RegistryEntry<Biome> biome, boolean isSnowSeason, int epoch, float bandWidth);
}
