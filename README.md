# Season Cache

**Minecraft 1.21.x  |  Fabric  |  Requires Serene Seasons**

Season Cache keeps snow and ice blocks in sync with your Serene Seasons configuration. When winter arrives, snow and ice appear on terrain. When spring comes, they clear. It also builds and maintains a server-authoritative per-chunk snow coverage map that companion mods such as Nova Reimagined Snow can use to drive accurate shader snow in Distant Horizons LOD terrain via Nova Reimagined shader pack.

---

## What it does

Without this mod, Serene Seasons changes the season but the world only gains or loses snow through Minecraft's slow random tick system. That process takes in-game days to visibly change large areas. Season Cache reconciles the entire loaded world immediately when a season transition occurs.

It also solves the LOD snow problem when used with the Nova Reimagined shader pack. Distant Horizons renders terrain far beyond vanilla render distance using its own geometry. Without accurate per-chunk snow data, shaders have no way to know which distant chunks should show snow. Season Cache builds a complete coverage map of the entire explored world and streams it to connected clients so Nova Reimagined can use it.

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

Season Cache works on both dedicated servers and singleplayer. On a dedicated server, install it server-side only. The client-side component handles shader integration when Nova Reimagined Snow is also installed on the client so install it client side when appropriate.

---

## Configuration

A config file is created at `config/seasoncache.json` on first launch.

**Cleanup mode**

Controls whether the reconciler only removes snow and ice, or also places it.

- `AGGRESSIVE` (default): removes snow in warm seasons and places it in winter. The world matches the season in both directions.
- `CONSERVATIVE`: removes snow and ice when the season no longer supports them but never places new snow. Use this if you want one-way cleanup only.

**Budget**

Controls how many chunks are reconciled per server tick. Higher values clear terrain faster at the cost of more tick time.

- `HIGH` (default): 8 chunks per tick, up to 10ms per tick
- `MEDIUM`: 3 chunks per tick, up to 3ms per tick
- `LOW`: 1 chunk per tick, up to 1ms per tick

**Other settings**

- `trackSnow`: enable or disable snow block reconciliation (default: true)
- `trackIce`: enable or disable ice block reconciliation (default: true)
- `neverTouchPerennialColumns`: skip permanently cold biomes such as frozen peaks and ice spikes (default: true)
- `hysteresisBandWidth`: softens the temperature threshold at biome boundaries to avoid sharp snow lines (default: 0.06)
- `proximityGateChunks`: reconciliation focuses on chunks near players first (default: 8)

---

## Commands

All commands require operator level 2.

`/seasoncache status` â€” shows current season, cleanup mode, queue size, coverage build progress, and cache statistics.

`/seasoncache mode aggressive` â€” switch to aggressive mode at runtime.

`/seasoncache mode conservative` â€” switch to conservative mode at runtime.

`/seasoncache invalidate all` â€” clears all stored epoch data and triggers a full rebuild. Use after making significant terrain changes or if coverage data looks wrong.

`/seasoncache debug <radius>` â€” shows temperature data for chunks around your position. Useful for diagnosing unexpected snow or no-snow areas.

---

## Performance notes

The initial coverage build after a fresh install or cache wipe reads region files from disk rather than running the world generator, but it will typically still take some time depending on the size of the world already generated. The server tick thread is not blocked during this process. My 25,000 block diameter world takes about an hour or so on my server.

Snow and ice reconciliation during season transitions uses a section-level block scan that skips chunk sections containing neither snow nor ice, making it fast enough to keep up with elytra flight at default budget settings....hopefully haha.

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
