# Throttling lag machines (anarchy servers)

Research, 2026-10-02. **[V]** means read in official docs or source code. **[C]** means
community or plugin-page claims. Nothing here is built yet; this is the design input.

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
