package com.seasoncache.core;

import com.seasoncache.SeasonCacheMod;
import com.seasoncache.config.SeasonCacheConfig;
import com.seasoncache.core.store.ChunkSeasonStore;
import com.seasoncache.integration.SeasonProvider;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Reconciles loaded chunks against the current seasonal snow/ice state.
 *
 * Decision model:
 *   1. Primary: coarse chunk-level boolean from getAuthoritativeChunkSnowState —
 *      the same signal driving the LOD shader.
 *   2. Neighbour gate (removal and addition): before acting on a coverage value
 *      that would change the chunk's state, check loaded cardinal neighbours.
 *      Loaded neighbours with coverage data vote snowy/not-snowy. Unloaded or
 *      data-absent neighbours abstain. If not-snowy votes don't clearly outweigh
 *      snowy votes, the decision is inconclusive.
 *   3. Multi-point fallback (only when inconclusive): sample 4 quadrant positions
 *      within the chunk via the same temperature oracle as the coverage builder.
 *      Majority of those samples determines the final decision.
 *
 * The neighbour gate and multi-point fallback only apply when coverage would
 * cause a state change (removal when snowy=false, addition when snowy=true).
 * Chunks where coverage agrees with current block state skip both checks entirely.
 *
 * Block scan: ChunkSection.hasAny() palette check skips sections with no snow or
 * ice without iterating their blocks. Only sections containing target blocks are
 * iterated — far cheaper than the previous per-column world-query approach.
 */
public final class ChunkSeasonReconciler {

    // Quadrant sample offsets within a 16x16 chunk (local coords).
    // Four points away from the center to avoid structure bias at (8,8).
    private static final int[][] QUADRANT_OFFSETS = { {4,4}, {12,4}, {4,12}, {12,12} };

    // Minimum not-snowy votes required from loaded neighbours to proceed with
    // removal without the multi-point fallback. Requires clear agreement.
    private static final int NOT_SNOWY_VOTE_THRESHOLD = 3;

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

    /**
     * Removal-only reconcile path for season transition.
     *
     * Called when the chunk was confirmed snowy=true in the outgoing epoch's
     * coverage snapshot. Uses multiPointSample to verify whether the chunk
     * should be snowy in the NEW season — biomes that remain cold across the
     * transition (snowy_taiga, snowy_plains etc.) correctly keep their snow.
     * Only removes snow if the new season says not-snowy. Placement for
     * chunks that stay snowy is left to the normal reconcile path (aggressive
     * mode), since this path is removal-focused.
     */
    public void reconcileRemoveOnly(ServerWorld world, ChunkPos chunkPos) {
        int currentEpoch = this.epochService.currentEpoch(world);

        if (this.store.isChunkClean(world, chunkPos, currentEpoch)) return;

        // Ask the new season: should this chunk still be snowy?
        // 4 SS temperature calls — cheap, and ensures we don't incorrectly
        // strip snow from cold biomes that remain below threshold in the new season.
        boolean snowy = multiPointSample(world, chunkPos);

        if (!snowy) {
            WorldChunk chunk = world.getChunk(chunkPos.x, chunkPos.z);
            ChunkSection[] sections = chunk.getSectionArray();
            int bottomY = world.getBottomY();
            BlockPos.Mutable pos = new BlockPos.Mutable();

            for (int sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
                ChunkSection section = sections[sectionIdx];
                if (section == null || section.isEmpty()) continue;
                if (!sectionMayContainSnowOrIce(section)) continue;

                int sectionBaseY = bottomY + sectionIdx * 16;

                for (int localY = 0; localY < 16; localY++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        for (int localX = 0; localX < 16; localX++) {
                            BlockState state = section.getBlockState(localX, localY, localZ);
                            if (state.isAir()) continue;

                            int worldX = chunkPos.getStartX() + localX;
                            int worldY = sectionBaseY + localY;
                            int worldZ = chunkPos.getStartZ() + localZ;
                            pos.set(worldX, worldY, worldZ);

                            if (this.config.trackSnow && state.isOf(Blocks.SNOW)) {
                                world.setBlockState(pos, Blocks.AIR.getDefaultState(),
                                        Block.NOTIFY_LISTENERS);
                                continue;
                            }
                            if (this.config.trackIce && state.isOf(Blocks.ICE)) {
                                world.setBlockState(pos, Blocks.WATER.getDefaultState(),
                                        Block.NOTIFY_LISTENERS);
                            }
                        }
                    }
                }
            }
        }

        this.store.setCoverageState(world, chunkPos, currentEpoch, snowy);
        this.store.markChunkCleared(world, chunkPos, currentEpoch);
        SeasonCacheMod.get().onChunkAuthoritativelyReconciled(world, chunkPos, currentEpoch);
    }

    public void reconcile(ServerWorld world, ChunkPos chunkPos) {
        int currentEpoch = this.epochService.currentEpoch(world);

        if (this.store.isChunkClean(world, chunkPos, currentEpoch)) return;

        // Primary decision: coarse chunk-level coverage authority.
        // If coverage hasn't been computed yet for the current epoch (e.g. during a
        // re-derive after a season transition), fall through to multiPointSample rather
        // than returning early — the transition window is exactly when we need to act,
        // and 4 SS temperature queries is far cheaper than leaving snow in place.
        Boolean coverageSnowy = this.store.getAuthoritativeChunkSnowState(world, chunkPos, currentEpoch);
        boolean snowy = (coverageSnowy != null)
                ? resolveSnowy(world, chunkPos, currentEpoch, coverageSnowy)
                : multiPointSample(world, chunkPos);

        boolean aggressive = this.config.cleanupMode == SeasonCacheConfig.CleanupMode.AGGRESSIVE;

        WorldChunk chunk = world.getChunk(chunkPos.x, chunkPos.z);
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = world.getBottomY();

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
            ChunkSection section = sections[sectionIdx];
            if (section == null || section.isEmpty()) continue;
            if (!sectionMayContainSnowOrIce(section)) continue;

            int sectionBaseY = bottomY + sectionIdx * 16;

            for (int localY = 0; localY < 16; localY++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        BlockState state = section.getBlockState(localX, localY, localZ);
                        if (state.isAir()) continue;

                        int worldX = chunkPos.getStartX() + localX;
                        int worldY = sectionBaseY + localY;
                        int worldZ = chunkPos.getStartZ() + localZ;
                        pos.set(worldX, worldY, worldZ);

                        if (this.config.trackSnow && state.isOf(Blocks.SNOW)) {
                            if (!snowy) {
                                world.setBlockState(pos, Blocks.AIR.getDefaultState(),
                                        Block.NOTIFY_LISTENERS);
                            }
                            continue;
                        }

                        if (this.config.trackIce && state.isOf(Blocks.ICE)) {
                            if (!snowy) {
                                world.setBlockState(pos, Blocks.WATER.getDefaultState(),
                                        Block.NOTIFY_LISTENERS);
                            }
                        }
                    }
                }
            }
        }

        if (aggressive && snowy) {
            placeSnowAndIce(world, chunkPos, bottomY, pos);
        }

        // Confirm the resolved state back to the store so the delta pipeline works.
        this.store.setCoverageState(world, chunkPos, currentEpoch, snowy);
        this.store.markChunkCleared(world, chunkPos, currentEpoch);
        SeasonCacheMod.get().onChunkAuthoritativelyReconciled(world, chunkPos, currentEpoch);
    }

    // -------------------------------------------------------------------------
    // Decision resolution — neighbour gate + multi-point fallback
    // -------------------------------------------------------------------------

    /**
     * Resolves the final snowy boolean for a chunk, applying neighbour gate and
     * multi-point fallback when coverage would cause a block state change.
     *
     * Fast path: if the chunk has no snow or ice blocks and coverage says not-snowy,
     * or if coverage says snowy and we're not in aggressive mode, there's nothing to
     * change — return coverage directly without any additional checks.
     *
     * Neighbour gate: checks loaded cardinal neighbours. Each neighbour with coverage
     * data in memory votes snowy or not-snowy. Unloaded or data-absent neighbours
     * abstain. If not-snowy votes clearly outnumber snowy votes (>= threshold), the
     * coverage value is trusted and returned. If snowy votes win or no clear majority
     * exists, the decision is inconclusive.
     *
     * Multi-point fallback: samples 4 quadrant positions within the chunk using the
     * same temperature oracle as the coverage builder. Majority of those 4 samples
     * determines the final decision. Only fires when neighbour gate is inconclusive.
     */
    private boolean resolveSnowy(ServerWorld world, ChunkPos chunkPos,
                                  int currentEpoch, boolean coverageSnowy) {
        // If coverage says snowy, we need the neighbour gate to protect against
        // spurious addition. If coverage says not-snowy, we need it to protect
        // against spurious removal. Either way, run the gate.
        int[] votes = countNeighbourVotes(world, chunkPos, currentEpoch);
        int snowyVotes    = votes[0];
        int notSnowyVotes = votes[1];
        // votes[2] = abstentions (unloaded/no data) — not used directly

        if (coverageSnowy) {
            // Coverage says snowy — should we add snow?
            // Clear not-snowy majority from neighbours overrides coverage.
            if (notSnowyVotes >= NOT_SNOWY_VOTE_THRESHOLD && snowyVotes == 0) {
                return multiPointSample(world, chunkPos);
            }
            // Neighbours agree or inconclusive — trust coverage.
            return true;
        } else {
            // Coverage says not-snowy — should we remove snow?
            // Clear not-snowy majority from neighbours confirms removal.
            if (notSnowyVotes >= NOT_SNOWY_VOTE_THRESHOLD && snowyVotes == 0) {
                return false;
            }
            // Snowy votes win or inconclusive — run multi-point to decide.
            return multiPointSample(world, chunkPos);
        }
    }

    /**
     * Counts snowy and not-snowy votes from the four cardinal neighbours.
     *
     * Returns int[3]: [snowyVotes, notSnowyVotes, abstentions].
     * Abstentions occur when a neighbour has no coverage data in memory —
     * either unloaded or not yet processed by the coverage builder.
     * Abstentions do not influence the vote outcome.
     */
    private int[] countNeighbourVotes(ServerWorld world, ChunkPos chunkPos, int currentEpoch) {
        int snowyVotes = 0;
        int notSnowyVotes = 0;
        int abstentions = 0;

        int[][] deltas = { {0,-1}, {0,1}, {-1,0}, {1,0} };
        for (int[] d : deltas) {
            ChunkPos neighbour = new ChunkPos(chunkPos.x + d[0], chunkPos.z + d[1]);
            Boolean state = this.store.getCoverageSnowState(world, neighbour, currentEpoch);
            if (state == null) {
                abstentions++;
            } else if (state) {
                snowyVotes++;
            } else {
                notSnowyVotes++;
            }
        }

        return new int[]{ snowyVotes, notSnowyVotes, abstentions };
    }

    /**
     * Samples 4 quadrant positions within the chunk using the same temperature
     * oracle as the coverage builder. Returns true if the majority (3 or more of 4)
     * sample positions indicate snowy conditions.
     *
     * Only called when the neighbour gate is inconclusive. The 4 quadrant positions
     * avoid the center point (8,8) which is susceptible to structure heightmap bias.
     */
    private boolean multiPointSample(ServerWorld world, ChunkPos chunkPos) {
        BlockPos.Mutable samplePos = new BlockPos.Mutable();
        int snowyCount = 0;

        for (int[] offset : QUADRANT_OFFSETS) {
            int worldX = chunkPos.getStartX() + offset[0];
            int worldZ = chunkPos.getStartZ() + offset[1];
            int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    worldX, worldZ) - 1;
            surfaceY = Math.max(surfaceY, world.getBottomY());
            samplePos.set(worldX, surfaceY, worldZ);

            RegistryEntry<Biome> biome = world.getBiome(samplePos);
            RuntimeTypes.CoverageSample sample = this.provider.snapshotCoverageSample(
                    world, samplePos, biome);
            if (sample.snowy()) snowyCount++;
        }

        // Majority: 3 or more of 4 samples must agree to return snowy.
        return snowyCount >= 3;
    }

    // -------------------------------------------------------------------------
    // Section fast-path
    // -------------------------------------------------------------------------

    private boolean sectionMayContainSnowOrIce(ChunkSection section) {
        return section.hasAny(state ->
                (this.config.trackSnow && state.isOf(Blocks.SNOW))
                || (this.config.trackIce && state.isOf(Blocks.ICE))
        );
    }

    // -------------------------------------------------------------------------
    // Aggressive placement
    // -------------------------------------------------------------------------

    private void placeSnowAndIce(ServerWorld world, ChunkPos chunkPos,
                                  int bottomY, BlockPos.Mutable pos) {
        BlockPos.Mutable abovePos = new BlockPos.Mutable();

        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int worldX = chunkPos.getStartX() + localX;
                int worldZ = chunkPos.getStartZ() + localZ;

                int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                        worldX, worldZ) - 1;
                if (topY < bottomY) continue;

                pos.set(worldX, topY, worldZ);
                abovePos.set(worldX, topY + 1, worldZ);

                if (this.config.trackSnow) {
                    BlockState aboveState = world.getBlockState(abovePos);
                    if (aboveState.isAir() && world.isSkyVisible(abovePos)) {
                        BlockState surfaceState = world.getBlockState(pos);
                        if (surfaceState.isFullCube(world, pos)
                                && Blocks.SNOW.getDefaultState().canPlaceAt(world, abovePos)) {
                            world.setBlockState(abovePos, Blocks.SNOW.getDefaultState(),
                                    Block.NOTIFY_LISTENERS);
                        }
                    }
                }

                if (this.config.trackIce) {
                    BlockState surfaceState = world.getBlockState(pos);
                    FluidState fluid = surfaceState.getFluidState();
                    if (!fluid.isEmpty() && fluid.isStill()
                            && fluid.getFluid() == Fluids.WATER
                            && isShorelineAdjacent(world, pos)) {
                        world.setBlockState(pos, Blocks.ICE.getDefaultState(),
                                Block.NOTIFY_LISTENERS);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shoreline helpers
    // -------------------------------------------------------------------------

    private static boolean isShorelineAdjacent(ServerWorld world, BlockPos waterPos) {
        int x = waterPos.getX();
        int y = waterPos.getY();
        int z = waterPos.getZ();
        BlockPos.Mutable check = new BlockPos.Mutable();

        check.set(x + 1, y, z); if (isSolidLand(world.getBlockState(check))) return true;
        check.set(x - 1, y, z); if (isSolidLand(world.getBlockState(check))) return true;
        check.set(x, y, z + 1); if (isSolidLand(world.getBlockState(check))) return true;
        check.set(x, y, z - 1); if (isSolidLand(world.getBlockState(check))) return true;

        return false;
    }

    private static boolean isSolidLand(BlockState state) {
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (state.isOf(Blocks.ICE) || state.isOf(Blocks.PACKED_ICE)
                || state.isOf(Blocks.BLUE_ICE)) return false;
        return true;
    }
}
