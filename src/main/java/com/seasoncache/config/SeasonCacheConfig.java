package com.seasoncache.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.seasoncache.core.RuntimeTypes.Budget;
import com.seasoncache.core.RuntimeTypes.BudgetProfile;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class SeasonCacheConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "seasoncache.json";

    /**
     * Controls whether the reconciler only removes snow/ice, or also places it.
     *
     * AGGRESSIVE (default): remove AND place snow/ice to match the current season.
     *   Snow appears during winter, disappears in spring/summer.
     *
     * CONSERVATIVE: remove-only. Never places new snow or ice.
     *
     */
    public enum CleanupMode {
        CONSERVATIVE,
        AGGRESSIVE
    }

    public boolean enabled = true;
    public boolean overworldOnly = true;

    public CleanupMode cleanupMode = CleanupMode.AGGRESSIVE;

    public boolean trackSnow = true;
    public boolean trackIce = true;

    /**
     * When true, columns classified as PERENNIAL_COLD (e.g. Ice Spikes, Snowy Plains)
     * are never touched. Snow and ice in those biomes are permanent, not seasonal.
     */
    public boolean neverTouchPerennialColumns = true;

    /**
     * Half-width of the hysteresis band around the 0.15f season temperature threshold.
     *
     * Columns whose SS-adjusted temperature falls within [0.15 - bandWidth, 0.15 + bandWidth]
     * receive probabilistic snow/ice coverage driven by a deterministic per-column noise
     * function, rather than the hard binary cut used outside the band.
     *
     * Outside the band the mod remains fully authoritative:
     *   adjustedTemp < (0.15 - bandWidth) → always snow/ice eligible
     *   adjustedTemp > (0.15 + bandWidth) → never snow/ice
     *
     * Inside the band, coverage probability ramps linearly from 1.0 (cold edge) to 0.0
     * (warm edge). The noise pattern is seeded on absolute world position and epoch, so
     * it is stable across revisits within the same epoch and refreshes at season change.
     *
     * Tuning guide:
     *   0.04 — narrow, transition is subtle, small elevation changes can still produce bands
     *   0.06 — default, spans typical within-biome elevation temperature variation
     *   0.10 — wide, very soft transition, mixes snow/clear terrain across a broader zone
     *
     * Changing this value invalidates all stored epoch data (triggers a full re-reconcile).
     */
    public float hysteresisBandWidth = 0.06f;

    public BudgetProfile gameplayBudget = BudgetProfile.HIGH;

    public int lowChunksPerTick = 1;
    public int mediumChunksPerTick = 3;
    public int highChunksPerTick = 8;

    public long lowMaxMillisPerTick = 1L;
    public long mediumMaxMillisPerTick = 3L;
    public long highMaxMillisPerTick = 10L;

    public int lowRegionsPerTick = 1;
    public int mediumRegionsPerTick = 4;
    public int highRegionsPerTick = 16;

    /**
     * PRECACHE budget — used for the initial server-start coverage build.
     *
     * precacheMaxMillisPerTick controls how long the tick thread spends per tick
     * draining completed heightmap batches from the IO thread. At 0.05–0.1 ms per
     * chunk this allows processing ~200–400 chunks per tick, keeping pace with the
     * IO thread's output without blocking other tick work for too long.
     *
     * precacheChunksPerTick controls the reconciler budget during the precache phase
     * (few loaded chunks at startup, so this rarely matters in practice).
     */
    public int  precacheChunksPerTick    = 16;
    public long precacheMaxMillisPerTick = 20L;
    public int  precacheRegionsPerTick   = 64;

    /**
     * Radius in chunks (Chebyshev distance) within which at least one online player
     * must be present for a queued chunk to be processed immediately.
     *
     * If no player is within this radius the chunk is deferred to the back of the
     * queue, keeping the reconcile budget focused on terrain near active players.
     * Deferral is capped by maxChunkDeferMs — once a chunk has waited that long it
     * is processed regardless of proximity, preventing starvation.
     *
     * Set to 0 to disable the proximity gate entirely (pure FIFO behaviour).
     *
     * Recommended range: 6–12. At 8, a medium budget of 3 chunks/tick reconciles
     * the visible neighbourhood of a moving player before distant chunks compete.
     */
    public int proximityGateChunks = 8;

    /**
     * Maximum time in milliseconds a chunk can be deferred by the proximity gate
     * before it is processed unconditionally, regardless of player proximity.
     *
     * Prevents indefinite starvation of chunks in areas with no nearby players.
     * With up to 20 players spread across the map, 30 seconds ensures any chunk
     * within the loaded area is reconciled within half a minute even if no player
     * is currently adjacent to it.
     *
     * Default: 30000 (30 seconds).
     */
    public long maxChunkDeferMs = 30_000L;

    public static SeasonCacheConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);

        if (!Files.exists(path)) {
            SeasonCacheConfig config = new SeasonCacheConfig();
            config.save();
            return config;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            SeasonCacheConfig loaded = GSON.fromJson(reader, SeasonCacheConfig.class);
            if (loaded == null) loaded = new SeasonCacheConfig();
            loaded.save();
            return loaded;
        } catch (Exception e) {
            return new SeasonCacheConfig();
        }
    }

    public void save() {
        try {
            Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * A hash of config values that affect what a "correct" chunk looks like.
     * A change here causes all stored epochs to be considered stale, triggering
     * a fresh reconcile pass across all chunks.
     */
    public int epochConfigHash() {
        return Objects.hash(
                this.overworldOnly,
                this.cleanupMode,
                this.trackSnow,
                this.trackIce,
                this.neverTouchPerennialColumns,
                this.hysteresisBandWidth
        );
    }

    public Budget budgetFor(BudgetProfile profile) {
        return switch (profile) {
            case LOW      -> new Budget(this.lowChunksPerTick,      this.lowMaxMillisPerTick,      this.lowRegionsPerTick);
            case MEDIUM   -> new Budget(this.mediumChunksPerTick,   this.mediumMaxMillisPerTick,   this.mediumRegionsPerTick);
            case HIGH     -> new Budget(this.highChunksPerTick,     this.highMaxMillisPerTick,     this.highRegionsPerTick);
            case PRECACHE -> new Budget(this.precacheChunksPerTick, this.precacheMaxMillisPerTick, this.precacheRegionsPerTick);
        };
    }
}
