package com.seasoncache.core;

public final class RuntimeTypes {
    private RuntimeTypes() {
    }

    public enum BudgetProfile {
        LOW,
        MEDIUM,
        HIGH,
        /**
         * Aggressive profile used for the initial server-start coverage build when
         * no players are online. Allows the tick thread to spend more time per tick
         * draining completed heightmap batches and the reconciler to process more
         * chunks per tick, finishing the initial build significantly faster.
         * Switches back to gameplayBudget automatically once the build completes.
         */
        PRECACHE
    }

    /**
     * Biome column classification used by the reconciler to decide whether to act.
     *
     * PERENNIAL_COLD:     Always frozen regardless of season (Ice Spikes, Snowy Plains, etc.).
     *                     Skipped when neverTouchPerennialColumns is true (the default).
     *                     Biomes that are cold by vanilla temperature but seasonal by SS
     *                     (e.g. Snowy Taiga) are promoted to SEASONAL_TEMPORARY at server
     *                     start via the SS-derived override set.
     *
     * SEASONAL_TEMPORARY: Can receive snow/ice in winter and should lose it in other seasons.
     *                     Primary target of reconciliation.
     *
     * NON_SEASONAL:       No precipitation possible (desert, badlands, etc.). Always skipped.
     *                     Sky-visibility is checked by the reconciler's outer loop before
     *                     classifyColumn is ever called, so there is no need for a separate
     *                     COVERED_OR_INVALID value.
     */
    public enum ColumnType {
        PERENNIAL_COLD,
        SEASONAL_TEMPORARY,
        NON_SEASONAL
    }

    public record Budget(int chunksPerTick, long maxMillisPerTick, int regionsPerTick) {
    }

    public record SeasonSnapshot(String providerId, String seasonKey) {
    }

    public record CoverageSeasonSnapshot() {
    }

    public record CoverageSample(boolean snowy) {
    }

    public record StaticChunkClimate(String biomeId, int surfaceY) {
    }

}
