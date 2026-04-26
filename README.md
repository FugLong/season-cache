# Season Cache

**Minecraft 1.21.x  |  Fabric  |  Requires Serene Seasons**

Season Cache keeps snow and ice blocks in sync with your Serene Seasons configuration. When winter arrives, snow and ice appear on terrain. When spring comes, they clear. It also builds and maintains a server-authoritative per-chunk snow coverage map that companion mods such as Nova Reimagined Snow can use to drive accurate shader snow in Distant Horizons LOD terrain.

---

## What it does

Without this mod, Serene Seasons changes the season but the world only gains or loses snow through Minecraft's slow random tick system. That process takes in-game days to visibly change large areas. Season Cache reconciles the entire loaded world immediately when a season transition occurs.

It also solves the LOD snow problem. Distant Horizons renders terrain far beyond vanilla render distance using its own geometry. Without accurate per-chunk snow data, shaders have no way to know which distant chunks should show snow. Season Cache builds a complete coverage map of the entire explored world and streams it to connected clients so shader packs can use it.

---

## Requirements

- Minecraft 1.21.x (Java Edition)
- Fabric Loader 0.16.0 or later
- Fabric API
- Serene Seasons 10.x for 1.21.x

Optional but recommended:

- Serene Seasons X Distant Horizons for full seasonal LOD coverage
- Nova Reimagined Shader Pack
- Nova Reimagined Snow (companion mod for shader snow coverage)
- Distant Horizons (LOD rendering)

---

## Installation

1. Install Fabric Loader and Fabric API
2. Install Serene Seasons
3. Place `seasoncache-<version>.jar` in your `mods` folder
4. Start the server or singleplayer world

Season Cache works on both dedicated servers and singleplayer. On a dedicated server, install it server-side only. The client-side component handles shader integration when Nova Reimagined Snow is also installed on the client.

---

## Configuration

A config file is created at `config/seasoncache.json` on first launch.

**Cleanup mode**

Controls whether the reconciler only removes snow and ice, or also places it.

- `AGGRESSIVE` (default): removes snow in warm seasons and places it in winter. The world matches the season in both directions.
- `CONSERVATIVE`: removes snow and ice when the season no longer supports them but never places new snow. Use this if you want one-way cleanup only.

**Budget**

Controls how many chunks are reconciled per server tick. Higher values clear terrain faster at the cost of more tick time.

- `HIGH` (default): 8 chunks per tick, up to 10 ms per tick
- `MEDIUM`: 3 chunks per tick, up to 3 ms per tick
- `LOW`: 1 chunk per tick, up to 1 ms per tick

**Other settings**

- `trackSnow`: enable or disable snow block reconciliation (default: true)
- `trackIce`: enable or disable ice block reconciliation (default: true)
- `neverTouchPerennialColumns`: skip permanently cold biomes such as frozen peaks and ice spikes (default: true)
- `hysteresisBandWidth`: softens the temperature threshold at biome boundaries to avoid sharp snow lines (default: 0.06)
- `proximityGateChunks`: reconciliation focuses on chunks near players first (default: 8)
- `maxChunkDeferMs`: maximum time a chunk can be deferred by the proximity gate before being processed unconditionally, preventing starvation in areas with no nearby players (default: 30000 ms)

---

## Commands

All commands require operator level 2.

`/seasoncache status` — shows current season, cleanup mode, pending derivations, coverage build progress, cache statistics, and invalidation progress when relevant.

`/seasoncache mode aggressive` — switch to aggressive mode at runtime.

`/seasoncache mode conservative` — switch to conservative mode at runtime.

`/seasoncache build low|high` — starts a coverage build at the specified budget without clearing existing data. Use when the builder stopped early or a new area was explored.

`/seasoncache rebuild low|high` — clears all stored data, then starts a fresh build at the specified budget. Use after significant terrain changes or if coverage data looks wrong.

`/seasoncache invalidate all` — clears all in-memory state immediately. On-disk epoch zeroing runs in the background at low priority. Use `/seasoncache status` to monitor progress. Wait for `invalidation=complete` before restarting the server if full consistency is required.

`/seasoncache debug` — shows temperature data for the 3×3 chunk grid centred on your position. For each chunk reports min/max/mean SS-adjusted temperature and the column count in the cold zone, hysteresis band, and warm zone. Useful for diagnosing unexpected snow or bare patches.

`/seasoncache debugstate` — shows cached store state for the 3×3 chunk grid centred on your position. For each chunk shows the static climate sample (biome ID + surface Y), the 12-bit season rule mask, the perennial flag, whether the chunk is marked clean for the current epoch, and the authoritative snow state.

`/seasoncache sweep` — force re-reconciles all currently loaded chunks regardless of their clean/swept status. Use after a bug fix that may have caused incorrect reconciliation results.

---

## How the coverage map works

On server start, Season Cache reads heightmap data from Anvil region files for the entire explored world without invoking the chunk generator. It samples the biome and Serene Seasons temperature at the surface of each chunk and classifies it as snowy or not for the current season. This produces a complete per-chunk coverage map stored in sidecar files alongside your world data.

When a player connects, the server sends the full coverage map as a snapshot sorted by distance from the player. As the world changes (season transitions, chunk loads), incremental updates are streamed to clients. Nova Reimagined Snow receives these updates and writes them to a GPU texture that the shader samples when rendering both vanilla and LOD terrain.

The sidecar files mean subsequent server starts are fast. Rather than re-deriving coverage from scratch, Season Cache pre-warms from the existing files in seconds and only re-derives chunks where the season has changed.

---

## Block tag: snow_placement_blacklist

In aggressive mode, snow is placed on any full-cube block where sky is visible and the biome is cold enough. The `seasoncache:snow_placement_blacklist` tag excludes blocks that are full-cube but should not accumulate seasonal snow — workstations, storage, and similar player-crafted items.

Default entries include: shulker boxes, crafting table, chest variants, furnace variants, enchanting table, anvil variants, bookshelf variants, cartography/fletching/smithing tables, loom, grindstone, jukebox, note block, dispenser, dropper, observer, beacon, and command blocks.

To add entries via datapack, create `data/seasoncache/tags/blocks/snow_placement_blacklist.json`:
```json
{ "replace": false, "values": ["yourmod:your_block"] }
```

---

## Performance notes

The initial coverage build after a fresh install or cache wipe reads region files from disk rather than running the world generator, so it typically completes in under two minutes for a fully explored world. The server tick thread is not blocked during this process.

Snow and ice reconciliation during season transitions uses the cached per-chunk season rule (derived once per cold-cache chunk) so no per-column SS temperature queries fire during normal gameplay. Removal is surface-only and skips chunk sections that contain no snow or ice blocks.

---

## Compatibility

| Environment | Status |
|---|---|
| Singleplayer | Yes |
| Dedicated server | Yes |
| Distant Horizons | Yes, via Nova Reimagined Snow |
| Nova Reimagined shader | Yes, via Nova Reimagined Snow |
| Other Fabric mods | Generally yes |
| Forge / NeoForge | No |

---

## License

MIT. See LICENSE for full terms.

---

## Credits

**ItsThatNova** — mod author  
GitHub: [https://github.com/ItsThatNova](https://github.com/ItsThatNova)
