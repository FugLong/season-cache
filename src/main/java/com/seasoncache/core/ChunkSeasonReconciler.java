package com.seasoncache.core;

import com.seasoncache.SeasonCacheMod;
import com.seasoncache.config.SeasonCacheConfig;
import com.seasoncache.core.store.ChunkSeasonStore;
import com.seasoncache.integration.SeasonProvider;
import com.seasoncache.ModTags;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.biome.Biome;
import sereneseasons.api.season.Season;
import sereneseasons.season.SeasonHooks;

/**
 * Chunk-granular loaded-chunk reconciler.
 *
 * Seasonal truth comes only from the persistent chunk rule cache. Runtime work is
 * limited to ensuring a rule exists, resolving the current season bit, applying
 * add/remove work for the chunk, and marking the chunk applied for the epoch.
 */
public final class ChunkSeasonReconciler {
    private static final float SNOW_FREEZE_THRESHOLD = 0.15f;

    private final SeasonCacheConfig config;
    private final SeasonProvider provider;
    private final SeasonEpochService epochService;
    private final ChunkSeasonStore store;

    public ChunkSeasonReconciler(
            SeasonCacheConfig config,
            SeasonProvider provider,
            SeasonEpochService epochService,
            ChunkSeasonStore store
    ) {
        this.config = config;
        this.provider = provider;
        this.epochService = epochService;
        this.store = store;
    }

    public void reconcile(ServerLevel world, ChunkPos chunkPos) {
        int currentEpoch = this.epochService.currentEpoch(world);
        if (this.store.isChunkClean(world, chunkPos, currentEpoch)) return;

        RuntimeTypes.ChunkSeasonRule rule = ensureChunkSeasonRule(world, chunkPos);
        if (rule == null) return;

        if (rule.perennialNoTouch() && this.config.neverTouchPerennialColumns) {
            this.store.markChunkCleared(world, chunkPos, currentEpoch);
            SeasonCacheMod.get().onChunkAuthoritativelyReconciled(world, chunkPos, currentEpoch);
            return;
        }

        int seasonIdx = currentSeasonIndex(world);
        if (seasonIdx < 0) {
            // Season key not in rule config — can't determine truth safely.
            // Skip without marking clean so the chunk is retried next tick.
            SeasonCacheMod.LOGGER.warn("Season Cache: unknown season key '{}' for chunk {} — skipping reconcile.",
                    this.provider.snapshot(world).seasonKey(), chunkPos);
            return;
        }

        boolean snowy = rule.isSnowyInSeason(seasonIdx);
        applyChunkTruth(world, chunkPos, snowy);
        this.store.markChunkCleared(world, chunkPos, currentEpoch);
        SeasonCacheMod.get().onChunkAuthoritativelyReconciled(world, chunkPos, currentEpoch);
    }

    public boolean prepareChunkRule(ServerLevel world, ChunkPos chunkPos) {
        return ensureChunkSeasonRule(world, chunkPos) != null;
    }

    private int currentSeasonIndex(ServerLevel world) {
        String seasonKey = this.provider.snapshot(world).seasonKey();
        return SeasonCacheMod.get().seasonRuleConfig().seasonIndex(seasonKey);
    }

    private RuntimeTypes.ChunkSeasonRule ensureChunkSeasonRule(ServerLevel world, ChunkPos chunkPos) {
        RuntimeTypes.ChunkSeasonRule cachedRule = this.store.getChunkSeasonRule(world, chunkPos);
        if (cachedRule != null) return cachedRule;

        RuntimeTypes.StaticChunkClimate staticSample = this.store.getStaticClimateSample(world, chunkPos);
        if (staticSample == null) {
            staticSample = createStaticClimateSample(world, chunkPos);
            if (staticSample == null) return null;
            this.store.setStaticClimateSample(world, chunkPos, staticSample.biomeId(), staticSample.surfaceY());
        }

        RuntimeTypes.ChunkSeasonRule rule = buildChunkSeasonRule(world, chunkPos, staticSample);
        if (rule == null) return null;
        this.store.setChunkSeasonRule(world, chunkPos, rule);
        return rule;
    }

    private RuntimeTypes.StaticChunkClimate createStaticClimateSample(ServerLevel world, ChunkPos chunkPos) {
        int worldX = chunkPos.getMinBlockX() + 8;
        int worldZ = chunkPos.getMinBlockZ() + 8;
        int surfaceY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
        surfaceY = Math.max(surfaceY, world.getMinY());
        BlockPos samplePos = new BlockPos(worldX, surfaceY, worldZ);
        Holder<Biome> biomeEntry = world.getBiome(samplePos);
        String biomeId = biomeEntry.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
        if (biomeId == null || biomeId.isBlank()) return null;
        return new RuntimeTypes.StaticChunkClimate(biomeId, surfaceY);
    }

    private RuntimeTypes.ChunkSeasonRule buildChunkSeasonRule(
            ServerLevel world,
            ChunkPos chunkPos,
            RuntimeTypes.StaticChunkClimate staticSample
    ) {
        int worldX = chunkPos.getMinBlockX() + 8;
        int worldZ = chunkPos.getMinBlockZ() + 8;
        BlockPos samplePos = new BlockPos(worldX, Math.max(staticSample.surfaceY(), world.getMinY()), worldZ);

        Holder<Biome> biomeEntry = resolveBiomeEntry(world, staticSample.biomeId());
        if (biomeEntry == null) {
            biomeEntry = world.getBiome(samplePos);
        }

        return buildChunkSeasonRule(samplePos, biomeEntry, SeasonCacheMod.get().seasonRuleConfig(), world.getSeaLevel());
    }

    public static RuntimeTypes.ChunkSeasonRule buildChunkSeasonRule(
            BlockPos samplePos,
            Holder<Biome> biomeEntry,
            RuntimeTypes.SeasonRuleConfig ruleConfig,
            int seaLevel
    ) {
        int mask = 0;
        if (ruleConfig.generateSnowIce() && biomeEntry.value().hasPrecipitation()) {
            Season.SubSeason[] subSeasons = Season.SubSeason.values();
            int limit = Math.min(12, subSeasons.length);
            for (int i = 0; i < limit; i++) {
                float seasonTemp = SeasonHooks.getBiomeTemperatureInSeason(
                        subSeasons[i], biomeEntry, samplePos, seaLevel);
                if (seasonTemp < SNOW_FREEZE_THRESHOLD) {
                    mask |= (1 << i);
                }
            }
        }

        boolean perennial = mask == 0xFFF;
        return new RuntimeTypes.ChunkSeasonRule(mask, perennial);
    }

    private Holder<Biome> resolveBiomeEntry(ServerLevel world, String biomeId) {
        if (biomeId == null || biomeId.isBlank()) return null;
        try {
            Identifier id = Identifier.parse(biomeId);
            return world.registryAccess().lookupOrThrow(Registries.BIOME)
                    .get(ResourceKey.create(Registries.BIOME, id))
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void applyChunkTruth(ServerLevel world, ChunkPos chunkPos, boolean snowy) {
        int bottomY = world.getMinY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Removal is surface-only, symmetric with placement. Scanning all sections
        // would destroy snow inside buildings, on tree decorations, and in structures.
        // The heightmap gives us the exact surface layer where seasonal snow lives.
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int worldX = chunkPos.getMinBlockX() + localX;
                int worldZ = chunkPos.getMinBlockZ() + localZ;
                // getTopY returns the first air block above the surface, so topY-1 is the
                // surface block (e.g. grass). Snow layers sit ON TOP of the surface at topY,
                // and ice replaces the surface block at topY-1. Check both positions so we
                // catch snow regardless of whether it is included in the heightmap or not.
                int surfaceY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
                if (surfaceY < bottomY) continue;

                if (!snowy) {
                    // Check topY (snow on top of surface)
                    pos.set(worldX, surfaceY + 1, worldZ);
                    BlockState above = world.getBlockState(pos);
                    if (this.config.trackSnow && above.is(Blocks.SNOW)) {
                        world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                    // Check topY-1 (ice in place of surface block, or snow if heightmap included it)
                    pos.set(worldX, surfaceY, worldZ);
                    BlockState surface = world.getBlockState(pos);
                    if (this.config.trackSnow && surface.is(Blocks.SNOW)) {
                        world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    } else if (this.config.trackIce && surface.is(Blocks.ICE)) {
                        world.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
                    }
                }
            }
        }

        if (snowy && this.config.cleanupMode == SeasonCacheConfig.CleanupMode.AGGRESSIVE) {
            placeSnowAndIce(world, chunkPos, bottomY);
        }
    }

    private void placeSnowAndIce(ServerLevel world, ChunkPos chunkPos, int bottomY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos abovePos = new BlockPos.MutableBlockPos();

        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int worldX = chunkPos.getMinBlockX() + localX;
                int worldZ = chunkPos.getMinBlockZ() + localZ;

                int topY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        worldX, worldZ) - 1;
                if (topY < bottomY) continue;

                pos.set(worldX, topY, worldZ);
                abovePos.set(worldX, topY + 1, worldZ);

                if (this.config.trackSnow) {
                    BlockState aboveState = world.getBlockState(abovePos);
                    if (aboveState.isAir() && world.canSeeSkyFromBelowWater(abovePos)) {
                        BlockState surfaceState = world.getBlockState(pos);
                        if (surfaceState.isFaceSturdy(world, pos, Direction.UP)
                                && !surfaceState.is(ModTags.SNOW_PLACEMENT_BLACKLIST)
                                && Blocks.SNOW.defaultBlockState().canSurvive(world, abovePos)) {
                            world.setBlock(abovePos, Blocks.SNOW.defaultBlockState(),
                                    3);
                        }
                    }
                }

                if (this.config.trackIce) {
                    BlockState surfaceState = world.getBlockState(pos);
                    FluidState fluid = surfaceState.getFluidState();
                    if (!fluid.isEmpty() && fluid.isSource()
                            && fluid.getType() == Fluids.WATER
                            && isShorelineAdjacent(world, pos)) {
                        world.setBlock(pos, Blocks.ICE.defaultBlockState(),
                                3);
                    }
                }
            }
        }
    }

    private static boolean isShorelineAdjacent(ServerLevel world, BlockPos waterPos) {
        int x = waterPos.getX();
        int y = waterPos.getY();
        int z = waterPos.getZ();
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();

        check.set(x + 1, y, z); if (isSolidLand(world.getBlockState(check))) return true;
        check.set(x - 1, y, z); if (isSolidLand(world.getBlockState(check))) return true;
        check.set(x, y, z + 1); if (isSolidLand(world.getBlockState(check))) return true;
        check.set(x, y, z - 1); if (isSolidLand(world.getBlockState(check))) return true;

        return false;
    }

    private static boolean isSolidLand(BlockState state) {
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE)) return false;
        return true;
    }
}
