# Changelog

## 1.3.1 — Bug fixes and dead code removal

### Bug fix — snow_placement_blacklist not applied

The `seasoncache:snow_placement_blacklist` block tag was defined and its default data file
was present, but `ChunkSeasonReconciler.placeSnowAndIce()` never checked it. In aggressive
mode, snow would be placed on crafting tables, chests, furnaces, and other full-cube
workstation/storage blocks. The tag check has been restored to the placement gate.

### Dead code removed — ChunkSeasonQueue

`ChunkSeasonQueue` and its tick/enqueue mechanism were fully dead — the main server tick
loop drives chunk sweeps via an internal `ArrayDeque` (`loadedSweepQueue`) and never
called `reconcileQueue.tick()` or enqueued to it. The class has been removed along with
all references in `SeasonCacheMod` and `SeasonCacheCommands`. The `queue=N` field has
been removed from `/seasoncache status` output (it always read 0).

`ChunkSeasonReconciler.reconcileRemoveOnly()` has also been removed — it was only ever
called from `ChunkSeasonQueue.tick()`.

### Compile fix — duplicate import

Removed a duplicate `import java.util.Set` in `SereneAwareSeasonProvider`.

---

## 1.3.0 — Fast section-scan reconciler, authoritative on-load coverage

### Reconciler rewrite

The loaded-chunk reconciler now uses the persistent per-chunk 12-season rule (a bitmask
computed once at cold-cache time) as the sole decision input. Runtime work is limited to
reading the current season bit and applying or removing snow/ice accordingly — no
per-column SS temperature query fires during normal operation.

Snow removal is surface-only (heightmap-driven), symmetric with placement. Section-level
iteration is avoided entirely: the reconciler acts only on the surface layer where seasonal
snow lives, not on decorations, interiors, or structures.

### Authoritative on-load state

When a chunk loads and its season rule already exists (warm-cache path), the mod immediately
sends the authoritative snow state to connected clients via `SeasonCacheSyncManager`. This
ensures chunks that skip reconcile (already correct from a prior session) still populate the
shader coverage texture with server-authoritative data rather than vanilla BiomeSampler values.

### Delayed season-change sweep

Season transitions arm a 40-tick deferred sweep rather than acting immediately, giving
Serene Seasons time to fully commit its internal state before reconciliation begins.
A generation token prevents stale sweeps from firing if a later event supersedes them.

### Coverage builder restart on season change

On season transition, the unloaded coverage builder restarts at MEDIUM budget so that
distant regions outside render distance receive updated shader coverage states for the
new epoch. On a warm cache (STATIC_ONLY path) this typically completes in seconds.

### Configuration defaults changed

- `cleanupMode`: `CONSERVATIVE` → `AGGRESSIVE`
- `gameplayBudget`: `MEDIUM` → `HIGH`

---

## 1.2.0 — IO-thread heightmap reading

The initial unloaded-world coverage build was moved entirely to a background IO thread
that reads Anvil region files directly. Build time dropped from multi-hour (chunk-generator
path) to tens of seconds for fully explored worlds.

Region files are read in batches; completed heightmap results drain back to the main thread
each tick for rule derivation and store writes. The tick thread never blocks on disk IO.

A dedicated background derivation thread (`season-cache-derive`) handles the 12-season
temperature computation per chunk, keeping the main thread free. Falls back to the IO thread
if derivation thread allocation fails (OOM).

Player neighbourhood priority: regions adjacent to online players are promoted to MEDIUM
priority. Cold regions load at LOW priority and do not compete with gameplay-adjacent work.

---

## 1.1.0 — Per-chunk season rule cache and persistent sidecars

Introduced the persistent 12-season bitmask per chunk (`ChunkSeasonRule`). The rule is
derived once per cold-cache chunk — computing `getBiomeTemperatureInSeason` for all 12
sub-seasons — and stored in sidecar files alongside world data.

Subsequent server starts pre-warm from sidecars in seconds. Only chunks whose rule was
derived under a different Serene Seasons configuration (detected via a stored hash of SS
temperature adjustments) require re-derivation.

Hysteresis band introduced: a configurable half-width around the 0.15f freeze threshold
where snow coverage is determined probabilistically by a deterministic per-column noise
function seeded on world position and epoch. Eliminates sharp snow-line artefacts at
biome boundaries.

Epoch service introduced: season + config + schema version hashed into a single int.
Chunks are marked clean per-epoch; re-visiting a chunk in a different epoch re-reconciles
it automatically.

---

## 1.0.0 — Initial public release

### Core features

- Snow and ice reconciliation for all loaded chunks on season transition, world start, and
  chunk load. Operates in two modes: `AGGRESSIVE` (place and remove) and `CONSERVATIVE`
  (remove only).
- Server-authoritative per-chunk snow coverage map streamed to clients via a snapshot +
  delta protocol. Intended for companion mods such as Nova Reimagined Snow to drive
  accurate shader snow in Distant Horizons LOD terrain.
- Two-tier column classification: fast biome gate (non-precipitating / perennially cold)
  eliminates most columns before any SS query fires. The seasonal override set (built once
  at server start via `SeasonHooks.hasPrecipitationSeasonal`) correctly promotes biomes
  like Snowy Taiga from perennially cold to seasonally temporary.
- Background IO thread for all sidecar disk reads and writes; tick thread never blocks.
- Epoch-change detection fires within one tick of an SS sub-season transition.

### Configuration

- `cleanupMode`: `CONSERVATIVE` or `AGGRESSIVE`.
- `gameplayBudget`: `LOW` / `MEDIUM` / `HIGH` — controls chunks/tick and ms/tick budget.
- `trackSnow`, `trackIce`: enable/disable per block type.
- `neverTouchPerennialColumns`: skip permanently frozen biomes (default true).
- `hysteresisBandWidth`: half-width of the temperature transition band (default 0.06).
- `proximityGateChunks`: radius within which reconcile budget is focused near players.
- `maxChunkDeferMs`: starvation guard — chunks deferred longer than this are processed
  unconditionally regardless of player proximity (default 30 000 ms).

### Commands

- `/seasoncache status` — live state, season, mode, coverage build progress.
- `/seasoncache mode aggressive|conservative` — switch mode at runtime.
- `/seasoncache build low|high` — start coverage build without clearing existing data.
- `/seasoncache rebuild low|high` — clear all data, then start a fresh build.
- `/seasoncache invalidate all` — clear in-memory state; on-disk zeroing runs in background.
- `/seasoncache debug` — per-column temperature diagnostics for the 3×3 chunk grid around
  the executor (min/max/mean, cold/band/warm distribution).
- `/seasoncache debugstate` — per-chunk store state for the 3×3 chunk grid (rule mask,
  climate sample, clean flag, authoritative snow state).
- `/seasoncache sweep` — force re-reconcile all loaded chunks.

### Block tag

`seasoncache:snow_placement_blacklist` — blocks excluded from aggressive-mode snow
placement even when the full-cube geometry check passes. Workstations, storage, and
similar player-crafted items are excluded by default. Datapack-extensible.
