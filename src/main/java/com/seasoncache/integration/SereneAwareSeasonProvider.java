package com.seasoncache.integration;

import com.seasoncache.SeasonCacheMod;
import com.seasoncache.core.RuntimeTypes;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.season.SeasonHooks;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * SeasonProvider implementation backed directly by the Serene Seasons API.
 * No reflection. SS is a compile-time dependency (libs/ folder) and a required
 * runtime dependency enforced by fabric.mod.json.
 *
 * Key SS API facts confirmed from jar inspection (10.1.0.1):
 *   SeasonHelper.getSeasonState(WorldAccess)            → ISeasonState
 *   ISeasonState.getSubSeason()                         → Season.SubSeason
 *   Season.SubSeason.getSerializedName()                → String
 *   SeasonHooks.hasPrecipitationSeasonal(World, Holder<Biome>) → boolean
 *   SeasonHooks.shouldSnow(LevelReader, Holder<Biome>, BlockPos) → boolean
 *   Biome.canSetIce intercepted by SS MixinBiome automatically
 */
public final class SereneAwareSeasonProvider implements SeasonProvider {
    private static final String PROVIDER_ID = "serene_seasons";
    private static final float PERENNIAL_COLD_THRESHOLD = 0.15f;

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public RuntimeTypes.SeasonSnapshot snapshot(ServerLevel world) {
        ISeasonState state = SeasonHelper.getSeasonState(world);
        String key = state.getSubSeason().getSerializedName();
        return new RuntimeTypes.SeasonSnapshot(PROVIDER_ID, key);
    }

    /**
     * Scans all registered biomes once at server start to build the set of biomes
     * that are below the vanilla perennial-cold temperature threshold but are
     * treated seasonally by Serene Seasons.
     *
     * The canonical example is Snowy Taiga (base temp 0.05f) — vanilla and our
     * temperature gate both classify it as perennially cold, but SS adjusts its
     * precipitation seasonally. Without this override it would never be cleared.
     *
     * Uses SeasonHooks.hasPrecipitationSeasonal() which is the authoritative SS
     * answer to "does SS treat this biome as having variable seasonal precipitation".
     * This call is only made here at startup — never in the reconcile hot path.
     *
     * Any modded biome with similar temperature/seasonality characteristics is
     * picked up automatically with no code changes required.
     */
    @Override
    public Set<Identifier> buildSeasonalOverrideSet(ServerLevel world,
            Iterable<? extends Holder<Biome>> allBiomes) {
        Set<Identifier> overrides = new HashSet<>();
        int scanned = 0;
        int overrideCount = 0;

        for (Holder<Biome> biomeEntry : allBiomes) {
            scanned++;
            Biome biome = biomeEntry.value();

            // Only examine biomes that would be classified PERENNIAL_COLD by temperature.
            // Above-threshold biomes are already SEASONAL_TEMPORARY — no override needed.
            if (!biome.hasPrecipitation() || biome.getBaseTemperature() >= PERENNIAL_COLD_THRESHOLD) {
                continue;
            }

            try {
                if (SeasonHooks.hasPrecipitationSeasonal(world, biomeEntry)) {
                    biomeEntry.unwrapKey().ifPresent(key -> overrides.add(key.identifier()));
                    overrideCount++;
                }
            } catch (Exception e) {
                // Log but don't fail — a biome missing from the override set is
                // conservative (stays perennial) rather than incorrect.
                SeasonCacheMod.LOGGER.warn(
                    "Season Cache: failed to query hasPrecipitationSeasonal for biome {}, " +
                    "treating as perennial cold.",
                    biomeEntry.unwrapKey().map(k -> k.identifier().toString()).orElse("unknown"), e);
            }
        }

        SeasonCacheMod.LOGGER.info(
            "Season Cache: biome scan complete. Scanned={} PerennialColdOverrides={}{}",
            scanned, overrideCount,
            overrideCount > 0 ? " " + overrides : "");

        return Collections.unmodifiableSet(overrides);
    }

    /**
     * Returns true if transitioning into the given Serene Seasons sub-season
     * requires the coverage builder to re-derive unloaded chunk snow coverage.
     *
     * Only the six shoulder sub-seasons around winter are active — these are the
     * only periods where biomes are crossing the 0.15f snow threshold and the
     * shader coverage picture is actually changing. The remaining six sub-seasons
     * (late spring through mid autumn) are thermally stable: no non-perennial biome
     * gains or loses snow coverage, so the previous epoch's coverage data remains
     * correct without any re-derivation work.
     *
     * Sub-season strings confirmed from SS 10.1.0.1 jar: the enum constants
     * (EARLY_SPRING etc.) serialise via name().toLowerCase(Locale.ROOT).
     */
    private static final Set<String> TRANSITION_SUB_SEASONS = Set.of(
            "late_autumn",
            "early_winter",
            "mid_winter",
            "late_winter",
            "early_spring",
            "mid_spring"
    );

    @Override
    public boolean requiresCoverageRederive(String seasonKey) {
        return TRANSITION_SUB_SEASONS.contains(seasonKey);
    }

    /**
     * Returns true if the current sub-season is any winter variant.
     * Fails safe to true (keep snow) on any exception.
     */
    @Override
    public boolean isSnowSeason(ServerLevel world) {
        try {
            ISeasonState state = SeasonHelper.getSeasonState(world);
            return state.getSubSeason().getSerializedName().contains("winter");
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public float sampleSeasonalTemperature(ServerLevel world, BlockPos pos,
            Holder<Biome> biomeEntry) {
        return SeasonHooks.getBiomeTemperature(world, biomeEntry, pos, world.getSeaLevel());
    }

    /**
     * Tier 1 biome gate — fast, no SS climate query in the hot path.
     *
     * TIER 1A — Non-precipitating biomes (desert, badlands, etc.):
     *   No snow or ice is ever possible. Excluded immediately.
     *
     * TIER 1B — Perennially cold biomes:
     *   Base temperature below PERENNIAL_COLD_THRESHOLD (0.15f).
     *   Skipped when neverTouchPerennialColumns is true (the default).
     *   EXCEPTION: if the biome's registry key is in seasonalColdOverrides
     *   (derived from SS at server start), it is promoted to SEASONAL_TEMPORARY
     *   despite the cold temperature. This handles Snowy Taiga and any other
     *   biome SS treats seasonally at sub-threshold temperatures.
     *
     * TIER 2: everything else is SEASONAL_TEMPORARY and passes to
     *   shouldHaveSnowNow / shouldHaveIceNow for per-column SS decisions.
     *
     * @param seasonalColdOverrides built once at server start, O(1) lookup here
     */
    @Override
    public RuntimeTypes.ColumnType classifyColumn(ServerLevel world, BlockPos pos,
            Holder<Biome> biomeEntry, Set<Identifier> seasonalColdOverrides) {
        Biome biome = biomeEntry.value();

        if (!biome.hasPrecipitation()) {
            return RuntimeTypes.ColumnType.NON_SEASONAL;
        }

        if (biome.getBaseTemperature() < PERENNIAL_COLD_THRESHOLD) {
            // Check if SS overrides this biome to be seasonal despite the cold temperature.
            boolean isSeasonalOverride = biomeEntry.unwrapKey()
                    .map(key -> seasonalColdOverrides.contains(key.identifier()))
                    .orElse(false);

            return isSeasonalOverride
                    ? RuntimeTypes.ColumnType.SEASONAL_TEMPORARY
                    : RuntimeTypes.ColumnType.PERENNIAL_COLD;
        }

        return RuntimeTypes.ColumnType.SEASONAL_TEMPORARY;
    }

    /**
     * Tier 2 snow decision.
     *
     * Non-winter fast path: biomes with base temperature >= PERENNIAL_COLD_THRESHOLD
     * return false immediately with no SS call. These biomes are unambiguously too
     * warm to snow outside winter — the base temperature alone is sufficient. This
     * makes non-winter reconciliation essentially free for the vast majority of terrain.
     *
     * Biomes with base temp < PERENNIAL_COLD_THRESHOLD (snowy_taiga, snowy_plains,
     * snowy_beach, etc.) always call getBiomeTemperature so SS can return their
     * seasonally-adjusted value. SS warms these in summer (→ false) but may keep
     * them cold in shoulder seasons (→ true based on ramp). This is also correct for
     * perennial cold biomes when neverTouchPerennialColumns=false.
     *
     * Only called on cold-cache chunks; results stored in the climate bit cache.
     */
    @Override
    public boolean shouldHaveSnowNow(ServerLevel world, BlockPos pos,
            Holder<Biome> biomeEntry, boolean isSnowSeason, int epoch, float bandWidth) {
        if (!isSnowSeason && biomeEntry.value().getBaseTemperature() >= PERENNIAL_COLD_THRESHOLD) {
            return false;
        }
        float adjustedTemp = SeasonHooks.getBiomeTemperature(world, biomeEntry, pos, world.getSeaLevel());
        return resolveWithHysteresis(adjustedTemp, pos.getX(), pos.getZ(), epoch,
                world.getSeed(), bandWidth);
    }

    /**
     * Tier 2 ice decision. Same fast path as shouldHaveSnowNow — biomes with base
     * temp >= PERENNIAL_COLD_THRESHOLD return false immediately in non-winter.
     * Spatial restriction to shoreline-adjacent cells is enforced by the reconciler.
     *
     * Only called on cold-cache chunks; results stored in the climate bit cache.
     */
    @Override
    public boolean shouldHaveIceNow(ServerLevel world, BlockPos pos,
            Holder<Biome> biomeEntry, boolean isSnowSeason, int epoch, float bandWidth) {
        if (!isSnowSeason && biomeEntry.value().getBaseTemperature() >= PERENNIAL_COLD_THRESHOLD) {
            return false;
        }
        float adjustedTemp = SeasonHooks.getBiomeTemperature(world, biomeEntry, pos, world.getSeaLevel());
        return resolveWithHysteresis(adjustedTemp, pos.getX(), pos.getZ(), epoch,
                world.getSeed(), bandWidth);
    }

    // -------------------------------------------------------------------------
    // Hysteresis helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves a snow/ice decision using a continuous coverage ramp + deterministic noise.
     *
     * Coverage probability:
     *   margin = threshold - adjustedTemp   (positive = colder than threshold)
     *   prob   = clamp(margin / bandWidth * 0.5 + 0.5, 0.0, 1.0)
     *
     * Outside the band (prob == 0 or 1) the result is returned immediately with no hash.
     * Inside the band the result is: columnNoise(x, z, epoch, seed) < prob.
     *
     * Using a continuous ramp (not discrete bands) ensures there are no secondary
     * threshold artefacts at band boundaries. The clamp guarantees authoritative
     * behaviour outside the ambiguous zone.
     */
    private static boolean resolveWithHysteresis(
            float adjustedTemp, int worldX, int worldZ,
            int epoch, long worldSeed, float bandWidth) {
        float margin = PERENNIAL_COLD_THRESHOLD - adjustedTemp; // positive = colder
        float prob = margin / bandWidth * 0.5f + 0.5f;

        if (prob >= 1.0f) return true;
        if (prob <= 0.0f) return false;

        return columnNoise(worldX, worldZ, epoch, worldSeed) < prob;
    }

    /**
     * Deterministic per-column noise in [0.0, 1.0).
     *
     * Uses a finalisation mix from MurmurHash3 / SplitMix64 applied to the combined
     * inputs. Absolute world coordinates (not chunk-relative) are used so the pattern
     * is spatially continuous across chunk boundaries, avoiding a 16-block grid artefact.
     *
     * Including the epoch in the mix means the pattern rotates at each season change
     * while remaining perfectly stable for revisits within the same epoch.
     */
    private static float columnNoise(int worldX, int worldZ, int epoch, long worldSeed) {
        long h = worldSeed
                ^ ((long) worldX * 0x9E3779B97F4A7C15L)
                ^ ((long) worldZ * 0x6C62272E07BB0142L)
                ^ ((long) epoch  * 0xBF58476D1CE4E5B9L);
        // SplitMix64 finalisation
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h =  h ^ (h >>> 31);
        // Map lower 32 bits to [0.0, 1.0)
        return (h & 0xFFFFFFFFL) / (float) 0x100000000L;
    }
}
