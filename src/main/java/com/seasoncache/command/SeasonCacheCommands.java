package com.seasoncache.command;

import com.mojang.brigadier.CommandDispatcher;
import com.seasoncache.SeasonCacheMod;
import com.seasoncache.config.SeasonCacheConfig;
import com.seasoncache.core.RuntimeTypes;
import net.minecraft.core.Holder;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.biome.Biome;
import sereneseasons.season.SeasonHooks;

public final class SeasonCacheCommands {
    private SeasonCacheCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("seasoncache")
                .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                .then(Commands.literal("status")
                        .executes(ctx -> runStatus(ctx.getSource())))
                .then(Commands.literal("mode")
                        .then(Commands.literal("conservative")
                                .executes(ctx -> setMode(ctx.getSource(), SeasonCacheConfig.CleanupMode.CONSERVATIVE)))
                        .then(Commands.literal("aggressive")
                                .executes(ctx -> setMode(ctx.getSource(), SeasonCacheConfig.CleanupMode.AGGRESSIVE))))
                .then(Commands.literal("build")
                        .then(Commands.literal("low")
                                .executes(ctx -> startBuild(ctx.getSource(), RuntimeTypes.BudgetProfile.LOW, false)))
                        .then(Commands.literal("high")
                                .executes(ctx -> startBuild(ctx.getSource(), RuntimeTypes.BudgetProfile.HIGH, false))))
                .then(Commands.literal("rebuild")
                        .then(Commands.literal("low")
                                .executes(ctx -> startBuild(ctx.getSource(), RuntimeTypes.BudgetProfile.LOW, true)))
                        .then(Commands.literal("high")
                                .executes(ctx -> startBuild(ctx.getSource(), RuntimeTypes.BudgetProfile.HIGH, true))))
                .then(Commands.literal("invalidate")
                        .then(Commands.literal("all")
                                .executes(ctx -> invalidateAll(ctx.getSource()))))
                .then(Commands.literal("debug")
                        .executes(ctx -> runTempDebug(ctx.getSource())))
                .then(Commands.literal("debugstate")
                        .executes(ctx -> runDebugState(ctx.getSource())))
                .then(Commands.literal("sweep")
                        .executes(ctx -> runSweep(ctx.getSource()))));
    }

    private static int runStatus(CommandSourceStack source) {
        SeasonCacheMod mod = SeasonCacheMod.get();
        ServerLevel overworld = source.getServer().overworld();
        if (overworld == null) {
            source.sendFailure(Component.literal("Overworld is not available."));
            return 0;
        }

        int currentEpoch = mod.epochService().currentEpoch(overworld);
        RuntimeTypes.SeasonSnapshot snapshot = mod.seasonProvider().snapshot(overworld);

        // Invalidation progress — shown only when a disk walk is in progress or recently completed.
        String invalidationStr = mod.ioThread().invalidationProgress();
        String invalidationSuffix = invalidationStr != null
                ? " | invalidation=" + invalidationStr
                  + (mod.ioThread().isInvalidationComplete() ? " (complete)" : " (running)")
                : "";

        source.sendSuccess(() -> Component.literal(
                "Season Cache"
                + " | provider=" + snapshot.providerId()
                + " | season=" + snapshot.seasonKey()
                + " | mode=" + mod.config().cleanupMode.name().toLowerCase()
                + " | derive=" + mod.pendingDerivationCount()
                + " | cleared=" + mod.store().clearedChunkCount(currentEpoch)
                + " | staticClimateCached=" + mod.store().cachedStaticClimateChunkCount()
                + " | dirty=" + mod.store().dirtyRegionCount()
                + " | coverageBuild=" + mod.coverageBuilder().processedFiles()
                           + "/" + mod.coverageBuilder().totalFiles()
                           + (mod.coverageBuilder().isActive() ? " (active)" : "")
                + invalidationSuffix
        ), false);

        return 1;
    }

    private static int setMode(CommandSourceStack source, SeasonCacheConfig.CleanupMode mode) {
        SeasonCacheMod mod = SeasonCacheMod.get();
        mod.config().cleanupMode = mode;
        mod.config().save();

        String description = switch (mode) {
            case CONSERVATIVE -> "remove-only (safe default)";
            case AGGRESSIVE   -> "remove and place (fully matches season)";
        };

        source.sendSuccess(() -> Component.literal(
                "Season Cache mode set to " + mode.name().toLowerCase() + " — " + description
        ), true);
        return 1;
    }

    private static int startBuild(CommandSourceStack source, RuntimeTypes.BudgetProfile profile, boolean invalidateFirst) {
        SeasonCacheMod mod = SeasonCacheMod.get();
        ServerLevel overworld = source.getServer().overworld();
        if (overworld == null) {
            source.sendFailure(Component.literal("Overworld is not available."));
            return 0;
        }

        if (invalidateFirst) {
            mod.store().invalidateAll(source.getServer());
        }

        mod.coverageBuilder().start(overworld, profile);
        source.sendSuccess(() -> Component.literal(
                (invalidateFirst ? "Rebuild" : "Build") + " started with "
                + profile.name().toLowerCase() + " budget."
        ), true);
        return 1;
    }

    private static int invalidateAll(CommandSourceStack source) {
        SeasonCacheMod mod = SeasonCacheMod.get();

        // In-memory state is cleared immediately on the tick thread.
        // On-disk zeroing runs in the background on the IO thread at LOW priority.
        // Use /seasoncache status to monitor the disk pass progress before restarting.
        mod.store().invalidateAll(source.getServer());
        ServerLevel overworld = source.getServer().overworld();
        if (overworld != null) {
            mod.coverageBuilder().start(overworld, mod.config().gameplayBudget);
        }

        source.sendSuccess(() -> Component.literal(
                "Season Cache: in-memory state cleared. " +
                "On-disk epoch zeroing is running in the background (LOW priority). " +
                "Use /seasoncache status to monitor progress. " +
                "Wait for invalidation=complete before restarting the server " +
                "if full consistency is required."
        ), true);
        return 1;
    }

    /**
     * /seasoncache debug
     *
     * Prints per-chunk temperature diagnostics for the 3x3 chunk grid centred on the
     * executor's position. For each chunk reports the min / max / mean SS-adjusted
     * temperature across all sky-visible, precipitating columns (sampled at surfacePos,
     * matching the reconciler and SS's weather tick exactly), plus a breakdown of how
     * many columns landed in the cold zone (prob=1), hysteresis band (0 < prob < 1),
     * and warm zone (prob=0).
     *
     * This lets you stand in a bare chunk during winter, run the command, and immediately
     * see whether its mean temperature is outside the band (explaining why no snow was
     * placed), inside the band (probabilistic — check the noise distribution), or at the
     * boundary of an adjacent chunk (explaining a sharp edge pattern).
     */
    private static int runTempDebug(CommandSourceStack source) {
        SeasonCacheMod mod = SeasonCacheMod.get();
        ServerLevel world = source.getLevel();

        float threshold = 0.15f;
        float bandWidth = mod.config().hysteresisBandWidth;
        float coldEdge  = threshold - bandWidth;
        float warmEdge  = threshold + bandWidth;

        ChunkPos playerChunk = ChunkPos.containing(BlockPos.containing(source.getPosition()));
        RuntimeTypes.SeasonSnapshot snapshot = mod.seasonProvider().snapshot(world);

        source.sendSuccess(() -> Component.literal(String.format(
                "[SC Debug | %s | band %.3f\u2013%.3f | chunk %d,%d]",
                snapshot.seasonKey(), coldEdge, warmEdge,
                playerChunk.x(), playerChunk.z())), false);

        BlockPos.MutableBlockPos surfacePos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos abovePos   = new BlockPos.MutableBlockPos();

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                ChunkPos chunkPos = new ChunkPos(playerChunk.x() + dx, playerChunk.z() + dz);

                float minTemp  =  Float.MAX_VALUE;
                float maxTemp  = -Float.MAX_VALUE;
                double sumTemp = 0.0;
                int coldCount  = 0;
                int bandCount  = 0;
                int warmCount  = 0;
                int scanned    = 0;

                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int worldX = chunkPos.getMinBlockX() + lx;
                        int worldZ = chunkPos.getMinBlockZ() + lz;
                        int topY   = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                                   worldX, worldZ) - 1;

                        if (topY < world.getMinY()) continue;

                        surfacePos.set(worldX, topY,     worldZ);
                        abovePos.set(  worldX, topY + 1, worldZ);

                        if (!world.canSeeSkyFromBelowWater(abovePos)) continue;

                        Holder<Biome> biomeEntry = world.getBiome(surfacePos);
                        if (!biomeEntry.value().hasPrecipitation()) continue;

                        float t = SeasonHooks.getBiomeTemperature(world, biomeEntry, surfacePos, world.getSeaLevel());

                        if (t < minTemp) minTemp = t;
                        if (t > maxTemp) maxTemp = t;
                        sumTemp += t;
                        scanned++;

                        if      (t < coldEdge) coldCount++;
                        else if (t > warmEdge) warmCount++;
                        else                   bandCount++;
                    }
                }

                final int fdx = dx, fdz = dz;
                if (scanned == 0) {
                    source.sendSuccess(() -> Component.literal(
                            String.format("(%+d,%+d) no sky-visible precipitating columns", fdx, fdz)
                    ), false);
                    continue;
                }

                final float fMin  = minTemp;
                final float fMax  = maxTemp;
                final float fMean = (float)(sumTemp / scanned);
                final int fN = scanned, fCold = coldCount, fBand = bandCount, fWarm = warmCount;

                source.sendSuccess(() -> Component.literal(String.format(
                        "(%+d,%+d) n=%-3d mean=%.3f min=%.3f max=%.3f | cold=%-3d band=%-3d warm=%d",
                        fdx, fdz, fN, fMean, fMin, fMax, fCold, fBand, fWarm
                )), false);
            }
        }

        return 1;
    }

    /**
     * /seasoncache debugstate
     *
     * Prints the cached store state for the 3x3 chunk grid centred on the executor.
     * For each chunk shows:
     *   - The cached static climate sample (biome ID + surface Y), or MISSING if not yet sampled
     *   - The persistent season rule: 12-bit snow mask in binary (one bit per sub-season,
     *     LSB = first season in SS order), the perennialNoTouch flag, and which seasons
     *     are snowy according to the mask
     *   - Whether the chunk has been marked clean (applied) for the current epoch
     *   - The authoritative snow state the mod believes this chunk should have right now
     *
     * Reading the mask: each character is a sub-season in orderedSeasonKeys order.
     * '1' = snowy that season, '0' = clear. The header line prints the full season
     * order so you can map positions to names.
     *
     * What to look for:
     *   MISSING rule    → chunk has not been processed by the coverage builder yet
     *   perennial=true  → all 12 seasons are snowy; reconciler skips this chunk entirely
     *                     when neverTouchPerennialColumns=true — snow will never be removed
     *   clean=false     → chunk is queued or has not been reconciled for this epoch yet
     *   snowy=false but snow visible → reconciler ran but applyChunkTruth missed it
     *                                  (e.g. surface Y was wrong, chunk was loaded mid-build)
     */
    private static int runDebugState(CommandSourceStack source) {
        SeasonCacheMod mod = SeasonCacheMod.get();
        ServerLevel world = source.getLevel();

        int currentEpoch = mod.epochService().currentEpoch(world);
        RuntimeTypes.SeasonSnapshot snapshot = mod.seasonProvider().snapshot(world);
        RuntimeTypes.SeasonRuleConfig ruleConfig = mod.seasonRuleConfig();
        int currentSeasonIndex = ruleConfig.seasonIndex(snapshot.seasonKey());

        // Build season order header — shows which bit position maps to which season
        java.util.List<String> orderedKeys = ruleConfig.orderedSeasonKeys();
        StringBuilder seasonHeader = new StringBuilder("seasons: ");
        for (int i = 0; i < orderedKeys.size(); i++) {
            if (i > 0) seasonHeader.append(", ");
            seasonHeader.append(orderedKeys.get(i)).append("(").append(i).append(")");
        }

        ChunkPos playerChunk = ChunkPos.containing(BlockPos.containing(source.getPosition()));

        source.sendSuccess(() -> Component.literal(String.format(
                "[SC DebugState | %s | idx=%d | epoch=%08x]",
                snapshot.seasonKey(), currentSeasonIndex, currentEpoch
        )), false);
        source.sendSuccess(() -> Component.literal(seasonHeader.toString()), false);

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                ChunkPos chunkPos = new ChunkPos(playerChunk.x() + dx, playerChunk.z() + dz);

                RuntimeTypes.StaticChunkClimate climate =
                        mod.store().getStaticClimateSample(world, chunkPos);
                RuntimeTypes.ChunkSeasonRule rule =
                        mod.store().getChunkSeasonRule(world, chunkPos);
                boolean clean = mod.store().isChunkClean(world, chunkPos, currentEpoch);
                Boolean snowy = mod.store().getAuthoritativeChunkSnowState(world, chunkPos, currentEpoch);

                final int fdx = dx, fdz = dz;

                if (climate == null && rule == null) {
                    source.sendSuccess(() -> Component.literal(String.format(
                            "(%+d,%+d) [NO CACHE] chunk not yet processed",
                            fdx, fdz
                    )), false);
                    continue;
                }

                // Build 12-char binary mask string, LSB first to match orderedSeasonKeys
                String maskBits;
                String snowySeasons;
                if (rule != null) {
                    StringBuilder bits = new StringBuilder();
                    StringBuilder snowy_sb = new StringBuilder();
                    int mask = rule.snowEpochMask();
                    int limit = Math.min(12, orderedKeys.size());
                    for (int i = 0; i < limit; i++) {
                        boolean bit = (mask & (1 << i)) != 0;
                        bits.append(bit ? '1' : '0');
                        if (bit) {
                            if (snowy_sb.length() > 0) snowy_sb.append('+');
                            snowy_sb.append(orderedKeys.get(i));
                        }
                    }
                    maskBits  = bits.toString();
                    snowySeasons = snowy_sb.length() > 0 ? snowy_sb.toString() : "none";
                } else {
                    maskBits     = "NO RULE";
                    snowySeasons = "?";
                }

                String climateStr = climate != null
                        ? String.format("biome=%-35s surf=%d", climate.biomeId(), climate.surfaceY())
                        : "climate=MISSING";
                String ruleStr = rule != null
                        ? String.format("mask=%s perennial=%-5b", maskBits, rule.perennialNoTouch())
                        : "rule=MISSING";
                String stateStr = String.format("clean=%-5b now=%s",
                        clean, snowy == null ? "?" : (snowy ? "SNOWY" : "clear"));

                source.sendSuccess(() -> Component.literal(String.format(
                        "(%+d,%+d) %s | %s | %s",
                        fdx, fdz, climateStr, ruleStr, stateStr
                )), false);

                // If snowy seasons is long, print it on a second line to keep lines readable
                if (rule != null && snowySeasons.length() > 60) {
                    final String fs = snowySeasons;
                    source.sendSuccess(() -> Component.literal(
                            "         snowy in: " + fs
                    ), false);
                }
            }
        }

        return 1;
    }

    private static int runSweep(CommandSourceStack source) {
        SeasonCacheMod mod = SeasonCacheMod.get();
        mod.forceSweepLoadedChunks(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "Season Cache: force sweep queued. All loaded chunks will be re-reconciled. Watch coverageBuild= in /seasoncache status."
        ), true);
        return 1;
    }
}
