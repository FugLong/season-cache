package com.seasoncache.command;

import com.mojang.brigadier.CommandDispatcher;
import com.seasoncache.SeasonCacheMod;
import com.seasoncache.config.SeasonCacheConfig;
import com.seasoncache.core.RuntimeTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import sereneseasons.season.SeasonHooks;

public final class SeasonCacheCommands {
    private SeasonCacheCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("seasoncache")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("status")
                        .executes(ctx -> runStatus(ctx.getSource())))
                .then(CommandManager.literal("mode")
                        .then(CommandManager.literal("conservative")
                                .executes(ctx -> setMode(ctx.getSource(), SeasonCacheConfig.CleanupMode.CONSERVATIVE)))
                        .then(CommandManager.literal("aggressive")
                                .executes(ctx -> setMode(ctx.getSource(), SeasonCacheConfig.CleanupMode.AGGRESSIVE))))
                .then(CommandManager.literal("build")
                        .then(CommandManager.literal("low")
                                .executes(ctx -> startBuild(ctx.getSource(), RuntimeTypes.BudgetProfile.LOW, false)))
                        .then(CommandManager.literal("high")
                                .executes(ctx -> startBuild(ctx.getSource(), RuntimeTypes.BudgetProfile.HIGH, false))))
                .then(CommandManager.literal("rebuild")
                        .then(CommandManager.literal("low")
                                .executes(ctx -> startBuild(ctx.getSource(), RuntimeTypes.BudgetProfile.LOW, true)))
                        .then(CommandManager.literal("high")
                                .executes(ctx -> startBuild(ctx.getSource(), RuntimeTypes.BudgetProfile.HIGH, true))))
                .then(CommandManager.literal("invalidate")
                        .then(CommandManager.literal("all")
                                .executes(ctx -> invalidateAll(ctx.getSource()))))
                .then(CommandManager.literal("debug")
                        .executes(ctx -> runTempDebug(ctx.getSource()))));
    }

    private static int runStatus(ServerCommandSource source) {
        SeasonCacheMod mod = SeasonCacheMod.get();
        ServerWorld overworld = source.getServer().getOverworld();
        if (overworld == null) {
            source.sendError(Text.literal("Overworld is not available."));
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

        source.sendFeedback(() -> Text.literal(
                "Season Cache"
                + " | provider=" + snapshot.providerId()
                + " | season=" + snapshot.seasonKey()
                + " | mode=" + mod.config().cleanupMode.name().toLowerCase()
                + " | queue=" + mod.reconcileQueue().size()
                + " | cleared=" + mod.store().clearedChunkCount(currentEpoch)
                + " | climateCached=" + mod.store().cachedClimateChunkCount(currentEpoch)
                + " | staticClimateCached=" + mod.store().cachedStaticClimateChunkCount()
                + " | coverageCached=" + mod.store().cachedCoverageChunkCount(currentEpoch)
                + " | cleanRegions=" + mod.store().fullyCleanRegionCount(currentEpoch)
                + " | dirty=" + mod.store().dirtyRegionCount()
                + " | precache=" + mod.precacheBuilder().processedFiles()
                           + "/" + mod.precacheBuilder().totalFiles()
                           + (mod.precacheBuilder().isActive() ? " (active)" : "")
                + " | coverageBuild=" + mod.coverageBuilder().processedFiles()
                           + "/" + mod.coverageBuilder().totalFiles()
                           + (mod.coverageBuilder().isActive() ? " (active)" : "")
                + invalidationSuffix
        ), false);

        return 1;
    }

    private static int setMode(ServerCommandSource source, SeasonCacheConfig.CleanupMode mode) {
        SeasonCacheMod mod = SeasonCacheMod.get();
        mod.config().cleanupMode = mode;
        mod.config().save();

        String description = switch (mode) {
            case CONSERVATIVE -> "remove-only (safe default)";
            case AGGRESSIVE   -> "remove and place (fully matches season)";
        };

        source.sendFeedback(() -> Text.literal(
                "Season Cache mode set to " + mode.name().toLowerCase() + " — " + description
        ), true);
        return 1;
    }

    private static int startBuild(ServerCommandSource source, RuntimeTypes.BudgetProfile profile, boolean invalidateFirst) {
        SeasonCacheMod mod = SeasonCacheMod.get();
        ServerWorld overworld = source.getServer().getOverworld();
        if (overworld == null) {
            source.sendError(Text.literal("Overworld is not available."));
            return 0;
        }

        if (invalidateFirst) {
            mod.store().invalidateAll(source.getServer());
            mod.reconcileQueue().clear();
        }

        mod.precacheBuilder().start(overworld, profile);
        mod.coverageBuilder().start(overworld, profile);
        source.sendFeedback(() -> Text.literal(
                (invalidateFirst ? "Rebuild" : "Build") + " started with "
                + profile.name().toLowerCase() + " budget."
        ), true);
        return 1;
    }

    private static int invalidateAll(ServerCommandSource source) {
        SeasonCacheMod mod = SeasonCacheMod.get();

        // In-memory state is cleared immediately on the tick thread.
        // On-disk zeroing runs in the background on the IO thread at LOW priority.
        // Use /seasoncache status to monitor the disk pass progress before restarting.
        mod.store().invalidateAll(source.getServer());
        mod.reconcileQueue().clear();
        ServerWorld overworld = source.getServer().getOverworld();
        if (overworld != null) {
            mod.coverageBuilder().start(overworld, mod.config().gameplayBudget);
        }

        source.sendFeedback(() -> Text.literal(
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
    private static int runTempDebug(ServerCommandSource source) {
        SeasonCacheMod mod = SeasonCacheMod.get();
        ServerWorld world = source.getWorld();

        float threshold = 0.15f;
        float bandWidth = mod.config().hysteresisBandWidth;
        float coldEdge  = threshold - bandWidth;
        float warmEdge  = threshold + bandWidth;

        ChunkPos playerChunk = new ChunkPos(BlockPos.ofFloored(source.getPosition()));
        RuntimeTypes.SeasonSnapshot snapshot = mod.seasonProvider().snapshot(world);

        source.sendFeedback(() -> Text.literal(String.format(
                "[SC Debug | %s | band %.3f\u2013%.3f | chunk %d,%d]",
                snapshot.seasonKey(), coldEdge, warmEdge,
                playerChunk.x, playerChunk.z)), false);

        BlockPos.Mutable surfacePos = new BlockPos.Mutable();
        BlockPos.Mutable abovePos   = new BlockPos.Mutable();

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                ChunkPos chunkPos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);

                float minTemp  =  Float.MAX_VALUE;
                float maxTemp  = -Float.MAX_VALUE;
                double sumTemp = 0.0;
                int coldCount  = 0;
                int bandCount  = 0;
                int warmCount  = 0;
                int scanned    = 0;

                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int worldX = chunkPos.getStartX() + lx;
                        int worldZ = chunkPos.getStartZ() + lz;
                        int topY   = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                                                   worldX, worldZ) - 1;

                        if (topY < world.getBottomY()) continue;

                        surfacePos.set(worldX, topY,     worldZ);
                        abovePos.set(  worldX, topY + 1, worldZ);

                        if (!world.isSkyVisible(abovePos)) continue;

                        RegistryEntry<Biome> biomeEntry = world.getBiome(surfacePos);
                        if (!biomeEntry.value().hasPrecipitation()) continue;

                        float t = SeasonHooks.getBiomeTemperature(world, biomeEntry, surfacePos);

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
                    source.sendFeedback(() -> Text.literal(
                            String.format("(%+d,%+d) no sky-visible precipitating columns", fdx, fdz)
                    ), false);
                    continue;
                }

                final float fMin  = minTemp;
                final float fMax  = maxTemp;
                final float fMean = (float)(sumTemp / scanned);
                final int fN = scanned, fCold = coldCount, fBand = bandCount, fWarm = warmCount;

                source.sendFeedback(() -> Text.literal(String.format(
                        "(%+d,%+d) n=%-3d mean=%.3f min=%.3f max=%.3f | cold=%-3d band=%-3d warm=%d",
                        fdx, fdz, fN, fMean, fMin, fMax, fCold, fBand, fWarm
                )), false);
            }
        }

        return 1;
    }
}
