package com.seasoncache;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/**
 * Block tag keys defined by this mod.
 *
 * Tags are resolved from the datapack layer at server start — no manual loading needed.
 * Server operators and mod authors can extend or override them via datapacks without
 * touching the jar.
 */
public final class ModTags {
    private ModTags() {}

    /**
     * Blocks excluded from aggressive-mode snow placement.
     *
     * The reconciler runs a full-cube geometry check first; this tag covers the subset
     * of full-cube blocks that are obviously player-crafted utility items (workstations,
     * storage, etc.) where natural weather-tick snow accumulation is more appropriate than
     * an immediate bulk placement by the reconciler.
     *
     * Default entries live in: data/seasoncache/tags/blocks/snow_placement_blacklist.json
     *
     * To extend: create a datapack with your own file at the same path.
     *   { "replace": false, "values": ["yourmod:your_block"] }
     *
     * To replace: use "replace": true to discard the defaults and supply your own full list.
     */
    public static final TagKey<Block> SNOW_PLACEMENT_BLACKLIST = TagKey.create(
            Registries.BLOCK,
            Identifier.fromNamespaceAndPath("seasoncache", "snow_placement_blacklist")
    );
}
