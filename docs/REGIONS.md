# Regions inside a dimension

Since 2026-10-02, branch `claude/project-thread-1u7hft`, **off by default** (`regions=false`,
`/mtmc regions on`).

Parallel dimensions only help when more than one dimension is busy. Regions split the entity
loop of **one** level into groups of nearby entities and tick the groups in parallel, the way
Folia does, but only for the entity phase. Block entities, scheduled ticks, random ticks,
spawning, the chunk system and players' connections still run on the level's thread, before
and after.

## How regions are made (`region/RegionTicker`)

Every tick, in `ServerLevel.tick`, the call `entityTickList.forEach(action)` is replaced:
1. The level is cut into square **cells** of `regionCellChunks` chunks (default 4, so 64
   blocks). A cell with a ticking entity in it is occupied. A passenger counts where its root
   vehicle is.
2. Occupied cells that touch, diagonals included, are joined into one **region** (union-find).
   Two regions are therefore always at least one whole cell apart. That gap is much more than
   an entity moves, reaches, senses or explodes in one tick.
3. Each region's entities keep their vanilla order. The regions are handed out biggest first
   to a pool (`MTMC Region #n`, `regionThreads`, default CPUs − 1). The level's thread takes
   regions too.
4. Each entity goes through the vanilla per-entity body unchanged (despawn check, ticking-range
   check, `guardEntityTick`).

Regions are rebuilt every tick, so they merge and split as mobs and players move, at no cost.

The vanilla loop runs instead when any of these is true:
* regions are off
* the profiler is on
* there are fewer than `regionMinEntities` entities
* there is only one region
* an ender dragon is in the level (the fight reaches the whole island)

`/mtmc regions` shows why.

## What region threads may do (`region/RegionPhase`)

Region threads read anything in the level freely, because nothing they share is written while
they run. Every write to state shared across regions goes through one of two doors.

**Exclusive.** The caller waits until every other region thread is parked between two entities
(or is itself waiting for its turn). It then takes the level's two owner fields
(`Level.thread`, `ServerChunkCache.mainThread`) and runs the write the vanilla way. The other
threads resume when it returns. Exclusive is reentrant. It covers:
* block changes, block entities and block events
* neighbour updates, scheduled ticks, explosions and `sendBlockUpdated` (it walks every
  navigating mob in the level)
* adding entities
* chunk loads and tickets: a region thread reads loaded chunks without touching the chunk
  cache's owner-only fast path, and only a miss goes exclusive (`region/RegionChunks`)
* POI claims and loads, and the village-distance tracker when a read (`isVillage`, used by
  iron golems and raids) has pending updates to run first
* scoreboards (kill score), stats, and advancement triggers a player actually listens to
* raids (joining, waves, leaders, the boss bar, hero of the village)

**Deferred.** These wait for the end of the phase and then run in the order they were asked
for, on the level's thread:
* entity-section bookkeeping: an entity moving into another 16-block section, or being removed
  (so the section storage is read-only while regions run)
* anything that can move an entity far: teleports, portals, ender pearl landings, pets
  teleporting to their owner
* command block minecarts
* waypoint (locator bar) updates

**Per thread.** Some state is replaced with a per-thread copy on region threads:
* `Level.random`: a `RegionRandom` (vanilla's `LegacyRandomSource` throws when two threads use
  it at once)
* the sensors' static `TargetingConditions`, as in the sensor phase
* the walk node evaluator's shared path-type cache, which is bypassed
* lag accounting samples, merged after the phase

After the phase, a check counts every entity that ended its tick in a cell owned by another
region (`escapes`). It should stay 0. A non-zero count means the gap between regions was too
small for something that moved; it logs the first 10.

## Mod compatibility

* **Lithium 0.25.3** (`mixin.experimental=true` too): `entity.inactive_navigations` keeps one
  level-wide set of active path navigations, and a mob adds or removes itself from inside its
  own tick. Two region threads corrupted it, which caused an NPE in `sendBlockUpdated` in the
  first lab run. `RegionLithiumDataMixin` (a `@Pseudo` mixin, so it does nothing without
  Lithium) makes that set synchronized. Lithium's other per-level data (block and
  entity-movement trackers) is written by hoppers and section moves, which run on the level's
  thread.
* **ServerCore 1.5.19** (`dynamic` on): tested together with Lithium.

Other mods that keep shared mutable state touched from an entity tick are not safe with
regions on.

## Lab (`lab/region_lab.py`)

All scenarios run on a server started by `run_server.sh` with variant `mtmcr[@N]`. That is
parallel dimensions plus regions, with N region threads. Results are appended to
`$LAB_DIR/region_lab.jsonl`.

* `ab`: pens of animals in a row, 256 blocks apart (`--pens`, `--pen-spacing`). MSPT is
  measured with `/mtmc regions` on and off, alternating.
* `tnt`: a crater, a chain and a deterministic cannon at each of N sites, 256 apart, with
  regions on and off. Each check must hold:
  * drops equal the destroyed blocks
  * every chain uses up all its TNT
  * each site's cannon ends in the same state on and off
* `chaos`: a zoo in every pen, then a sprint. The zoo has villagers with job sites and beds,
  zombies, skeletons, creepers, endermen, sheep, chickens, wolves, golems, bees, foxes, cats,
  witches, pillagers, items and TNT. The run fails on any exception or watchdog in the log,
  any diag counter, or any escape.

Results: [LAB_RESULTS.md](LAB_RESULTS.md), "Regions".

## Open items

* **Parked time.** Every exclusive section stops all region threads. In the chaos run,
  `add_entity`, `set_block` and `poi` were the most frequent ones. Deferring entity adds would
  cut most of the parking.
* **Untested with real players** and with the EmmaBot suites. The lab used only mobs.
* **Reads that write.** A vanilla read that quietly updates shared state (like the village
  distance tracker, found by the chaos run) is a crash waiting for the right mob. The chaos
  scenario is the net for these; a longer and more varied zoo would catch more.
* **Not covered:** a region thread never touches block entities, random ticks or spawning;
  those stay serial and are the next candidates.
