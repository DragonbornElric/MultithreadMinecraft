# Throttling lag machines (anarchy servers)

Research, 2026-10-02. **[V]** means read in official docs or source code. **[C]** means
community or plugin-page claims.

**Status (branch `ccr-c36973eb-axes2h-next`):**
* **Built:** steps 1 (accounting, `/mtmc lag`) and 2 (throttle by slowing) of the design below.
  Lab results are at the end of this file.
* **Not built:** steps 3 (hard caps) and 4 (freeze escalation).

## What lag machines load

| Machine | Subsystem it loads |
| --- | --- |
| Redstone dust clocks, comparator/lectern chains | Neighbour and shape updates. Vanilla dust sends many redundant updates per change [V, Alternate Current]. |
| Observer clocks, piston spam, zero-tick | Scheduled block ticks, block events, moving-piston block entities, light, block-change packets |
| BUD chains / update cascades | The neighbour-update queue. Capped by `max-chained-neighbor-updates` (1,000,000); past the cap, updates are skipped. That is the post-1.19 "update suppression", which has been used for dupes [V]. |
| Light spam | Light engine and light packets |
| Fluid / flooding machines | Fluid scheduled ticks and neighbour updates |
| Falling-block and TNT dupers | Entity ticks, collisions, explosions, chunk saves |
| Hopper and item spam | Block-entity ticks, inventory scans, item merging, tracking packets |
| Cramming and minecart/boat stacks | Collisions, roughly O(n²) per stack |
| Armor stands, item frames, map art | Tracking and metadata packets |
| Chunk-loading abuse (pearl stasis, portals, fast travel) | Chunk I/O and generation, plus ticking those chunks |
| Mob farms, villager halls | AI. See AI_OFFLOAD.md: brains, POI scans, pathfinding. |
| Book ban / chunk ban | Packet size; clients are kicked or can't load the chunk |
| Sculk chains, kelp/scaffolding growth | Game events, scheduled and random ticks |

## What exists

**Vanilla** [V]:
* `max-chained-neighbor-updates`
* `max_entity_cramming`, `max_command_sequence_length`, `max_command_forks`
* `rate-limit`
* simulation distance
* `/tick freeze|step|rate`
* `max-tick-time` is a watchdog (shutdown), not a throttle.

**Paper** [V]:
* `environment.max-block-ticks` / `max-fluid-ticks`: 65536 per tick, a hard per-tick budget;
  the excess waits.
* `collisions.max-entity-collisions`: 8
* `entity-per-chunk-save-limit`
* redstone implementation choice: vanilla / Eigencraft / Alternate Current
* hopper cooldowns
* `tick-rates.*`: per-type sensor and behaviour rates for villagers, spawners, …
* `packet-limiter`: 500 packets per 7 s, kick
* book and item size validation
* chunk load/send/generate rates per player
* piston-duplication fixes

**Spigot** [V]:
* entity activation range
* hopper transfer and check rates
* `max-tnt-per-tick` 100
* `max-tick-time`: disabled by Paper, because skipping ticks breaks block entities that
  expect a steady rate and leaves entities unticked for seconds.

**Fabric:**
* **Alternate Current** [V]: up to 20× cheaper redstone dust, but not exact vanilla update
  order.
* **Lithium** (no behaviour change)
* **ServerCore** [V, README]: activation range, dynamic distances and mobcaps, villager
  lobotomy, `/statistics`.
* **Carpet** `/tick health` and `/tick entities` [V, wiki]
* **spark**
* **Neruina** [C]: removes an entity or block entity that crashes while ticking, instead of
  crashing the server.

**Plugins built for lag machines:**
* **AnarchyExploitFixes** (source read) [V], the de-facto anarchy toolkit:
  * It counts events per region (default radius **1500 blocks**): redstone, pistons, liquids,
    block physics, explosions, sculk, spawns, pathfinding, targeting, growth.
  * If a type passes its limit within a time window, it cancels that activity in the whole
    region for a pause.
  * Defaults, e.g. redstone: 6000 per 20 s, observers 1500, comparators 2000; pause 18 s,
    current set to 0.
  * It also pauses everything globally when TPS ≤ 10 or MSPT ≥ 120.
  * Per-chunk caps: items 200, tile entities 100, vehicles 25, falling blocks 60.
  * Book-ban and chunk-ban checks.
* **LagAssist** [C]: removes fast observers, chunk scoring.
* **AntiRedstoneLag** [C]: 500 redstone updates per chunk.

**Anarchy servers** [C, weak sources]:
* 2b2t: elytra disabled or speed-capped for chunk-load reasons, kicks and anticheat for
  movement abuse.
* No public configs found for redstone or wither limits.

**Folia** [V]: each region ticks on its own. A lagging region slows only itself, and redstone
deadlines are kept when regions merge or split.

## Pitfalls (why this is hard)

1. **False positives on legit builds.** Event counts hit sorting systems, flying machines,
   TNT tunnelers and lava farms. Per-type limits and long windows help but don't fix it.
2. **Cancelling breaks machines.** Skipping ticks or cancelling one update mid-circuit leaves
   stuck clocks, headless pistons or half-moved blocks. That is Aikar's case against
   `max-tick-time`, and update skipping is itself a dupe vector.
3. **Griefing through the throttle.** With a 1500-block pause radius (AEF's default), anyone
   can trip the limit next to someone else's base and freeze it. The global TPS pause freezes
   everyone's redstone.
4. **Freezing mid-transfer is a dupe vector:** block events, pistons, hoppers, unloading
   during an update.

## Proposed design for MultithreadMC

**Principles:**
* measure time, not event counts
* attribute it to the chunk that caused it
* throttle whole chunks at tick boundaries, never in the middle of an update chain

1. **Accounting (build first, measurement only).** Time per chunk and category, each tick:
   * scheduled block and fluid ticks: `LevelTicks` runs ticks per position, so the cost
     goes to that chunk
   * block events
   * block entities (per ticker)
   * entities: time by the chunk the entity is in
   * neighbour updates: the update queue runs per position, so the cost goes to the chunk
     of the block being updated
   * light: queue sizes per chunk

   It keeps a decaying score per chunk. `/mtmc lag top` lists the worst chunks with their
   category, coordinates and owner hints (nearest player, last placer if tracked). This alone
   lets admins find and remove lag machines, and gives real numbers before anything is
   throttled.
2. **Throttle hot chunks by slowing them, not by cancelling.** When a chunk's score stays
   above a budget (e.g. more than X ms/tick for Y seconds, X relative to the tick budget),
   that chunk's scheduled ticks, block events and block entities run only every Nth tick.
   This is a Folia-style local slowdown:
   * The chunk's own ticks are **deferred, not dropped**. They stay in `LevelTicks` with
     their trigger time and run later, in their original order. Clocks just run slower, and
     nothing half-moves.
   * The slow-down starts and ends only at tick boundaries.
   * It only affects the offending chunks, so nobody can freeze a whole region by tripping a
     limit somewhere.
   * The open question is chains that cross into neighbouring chunks: updates from a slowed
     chunk into a normal one still apply normally. That is safe (it's ordinary chunk-border
     redstone timing) but needs lab tests.
3. **Hard caps for things that aren't timing:**
   * per-chunk entity caps by category (items, vehicles, falling blocks, armor stands)
   * max primed TNT per chunk per tick, with the extra delayed rather than deleted
   * per-player packet rates
   * book/NBT size

   The packet and NBT parts can come from existing mods; we don't need to rebuild them.
4. **Escalation:** past a second, much higher threshold, freeze the chunk (`/tick`-style:
   no ticks at all) and notify the admins. Never automatically delete player builds.
5. **Lab:**
   * a set of reference lag machines (observer clock grids, piston spam, hopper and item
     floods, minecart stacks, light spam, fluid floods) next to reference legit builds
     (sorting system, iron farm, flying machine, TNT tunneler)
   * pass condition: legit builds keep working, machines get slowed or frozen, and MSPT
     stays under budget
   * the cannon and TNT fingerprints catch any ordering change

## Not verified

* Pufferfish's exact config (its docs page was down)
* Carpet `/profile` details
* 2b2t, Constantiam and 9b9t internal configs
* LagAssist and AntiRedstoneLag internals

## Built: accounting and throttle (2026-10-02)

**Hooks** (whole-method wraps on ServerLevel and the block-entity ticker, so they are robust to
mods that rewrite bodies):

| Hook | Accounting | Throttle, on a slowed chunk's off ticks |
| --- | --- | --- |
| `tickNonPassenger` | timed, charged to the entity's chunk | skipped (never players, nor vehicles carrying one) |
| block-entity ticker `tick` | timed | `shouldTickBlocksAt` says no, so it skips |
| `tickBlock` / `tickFluid` | timed, charged to the tick's position | the `LevelTicks` chunk check (`isPositionTickingWithEntitiesLoaded`) says no, so the chunk's ticks stay queued, in order |
| `doBlockEvent` | timed | `shouldTickBlocksAt` says no, so vanilla reschedules the event |
| `tickChunk` (random ticks, snow, lightning) | timed | skipped |

Nested work is counted once, in the outer tick. Numbers are 1 s windows folded into a ~5 s
average and a decaying peak.

**Escalation:**
* Over budget for 2 windows in a row: one level slower (1 in 2^k ticks, each chunk with its
  own phase).
* "Over budget" means over `lagChunkBudgetMs` while the server is busy, or over
  `lagHardBudgetMs` regardless.
* Easing off: 5 windows in which the chunk would be under half the budget one level faster.

**Lab** (`lab/lag_lab.py`, `cloud-4`):

*detect:* builds added one at a time; MSPT added vs what `/mtmc lag` charged:

| Build | MSPT added | Charged |
| --- | --- | --- |
| 300-minecart stack | +31.13 | 30.51 |
| 128 observer-piston clocks | +2.33 | 1.85 |
| 256 observer-lamp clocks | +1.20 | 0.92 |
| 40-cow farm | +0.63 | 0.76 |
| hopper chain, repeater clock | ~0 | 0.07, 0.17 |

The ranking puts the three machines on top.

*overhead:* accounting on vs off, 3000 mobs over 3 dimensions, alternating every 40 s:
32.0 vs 30.9 MSPT (3.6%). That is within the ±3 ms sample noise, so treat it as at most ~4%.

*throttle:* a hot chunk (300 minecarts, an observer clock counting pulses into a scoreboard, a
chest→10 hoppers→chest line with 64 stone) next to an identical cool chunk without minecarts:

| | Before | Throttle on | After release |
| --- | --- | --- | --- |
| MSPT | 48.5 | 3.05 | |
| Hot chunk | | slowed 1/2 → 1/4 → 1/8 → 1/16, then held (1.9 ms ≤ budget) | |
| Hot clock (pulses/s) | 3.3 | 0.4 | 3.4 |
| Cool clock (pulses/s) | 3.3 | 3.35 | 3.3 |
| Stone in hopper lines (hot/cool) | | 64/64, 64/64 | |
| Minecarts | | 300/300 | |

**Known gaps:**
* **Light updates** are computed on the light engine's own thread, so a light-spam machine
  costs CPU that doesn't appear in tick time. It needs its own counter (pending light work per
  chunk).
* **Network-side machines** (map art, book bans, packet spam) aren't tick time either. Use
  packet and NBT limits.
* **Neighbour updates from a normal chunk into a slowed one** still apply immediately, as
  vanilla does for chunks outside simulation distance.
* **Entities that cross chunks** are charged to the chunk they start the tick in.
