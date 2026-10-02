# MultithreadMC design (v0.1, Minecraft 26.2, Fabric)

## What it does

`MinecraftServer.tickChildren` ticks the levels one after another. MultithreadMC lets that
loop collect the levels instead, then ticks them together on worker threads at the point where
the loop would have finished (just before `tickConnection`). The rest of the tick stays where
vanilla has it, on the server thread:
* functions
* clocks
* connections and player packet handling
* players' `doTick` (and so their portal use)
* the player list
* chunk sending

Within a level nothing changes: entities, block entities, redstone, scheduled ticks and
spawning run in vanilla order on that level's worker.

With `threads=0` (the default) each level gets its own worker, so a vanilla world uses 3.

With `threads=N` and N smaller than the number of levels:
* each worker ticks a group of levels one after another
* groups are filled longest-processing-time first, from each level's smoothed tick time, so the
  busiest level gets a worker to itself
* below 2 threads the levels tick on the server thread exactly as in vanilla

## Ownership

Vanilla 26.2 has two thread fields that make a level single-owner:

| Field | Off-owner behaviour |
| --- | --- |
| `Level.thread` | `getBlockEntity` → `null` |
| `ServerChunkCache.mainThread` | `getChunk` → submit to the owner's queue and join; `getChunkNow` → `null`; it is also the thread the cache's `MainThreadExecutor` wakes |

For the phase, each worker swaps both fields to itself for the levels it owns (`@Mutable`
accessors). When every worker is done, it swaps them back to the server thread. Inside the
level, the code keeps vanilla's single-thread assumptions, now on the worker.

A worker that finishes early keeps draining its levels' chunk queues until all workers are
done, so a request from another worker still gets answered.

## What crosses levels, and how each case is handled

| Path | Problem | Handling |
| --- | --- | --- |
| Overworld clock (`tickTime`) | The Nether and End read game time from the Overworld's level data (`DerivedLevelData`); in parallel they would see `t` or `t+1` at random. `tickTime` also runs `/schedule`d functions. | Run on the server thread before the phase. The in-tick call is skipped (`ServerLevelMixin`). |
| Entity portals (`Entity.handlePortal`) | Finds or builds the portal in the destination level, which another worker is ticking | Deferred to after the phase. It still runs once per tick, so portal timers are unchanged. |
| Teleport into or out of a level this worker doesn't own (`Entity.teleport`, `ServerPlayer.teleport`) | Removes from one level's entity manager and adds to another's | Deferred. Returns `null` to the caller, which vanilla callers already treat as "did not teleport". |
| Ender pearl whose owner is in another level | Teleports the owner across, then damages them | The whole landing (`onHit`) is deferred, so the damage isn't lost. |
| Command blocks and command-block minecarts | A command can name any dimension (`execute in`, `/tp`, `/setblock`) | Deferred, still within the same tick (`deferCommandBlocks`). The lab shows they run exactly once per tick. |
| Everyone asleep | Moves the server-wide clock | Deferred. |
| `SavedDataStorage` (maps, map index, raids index, random sequences) | A plain `HashMap` reachable from any level, e.g. held maps updating in two dimensions | `computeIfAbsent`, `get` and `set` are `synchronized`. |
| `getChunk` into another level | Joins on the other level's queue | Hooked at the head of `getChunk`: the request goes through the public `getChunkFuture` to the owner's queue, and the asking worker runs its own levels' queues while it waits, so two workers waiting on each other cannot deadlock. Counted and logged as `cross_level`. The hook is at the method head because Lithium (and Moonrise) replace the method body. `/mtmc selftest` exercises this path on purpose. |

Deferred work runs on the server thread in the order it was requested, right after all
workers have finished, with ownership already back on the server thread.

## Ownership guard (`OwnerGuard`, since 2026-10-02)

A level's chunk system belongs to one thread at a time: the server thread, or the level's
worker during the parallel phase (`ServerChunkCache.mainThread`). This covers the ChunkMap, the
DistanceManager, the tickets and the unload queue. Vanilla never checks this, and its fastutil
maps corrupt silently when two threads write at once. The next lookup can then spin forever.

That is the likely cause of the owner's 60 s watchdog in `ChunkMap.processUnloads`
(2026-10-02, `lab/results/eesmp-scale-2026-10-02/`).

The guard sits on the entry points: `addTicket`, `addTicketWithRadius`,
`removeTicketWithRadius`, `runDistanceManagerUpdates`, `save`, `updateChunkForced`,
`ChunkMap.updateChunkScheduling`, `processUnloads` and `scheduleUnload`. When a call comes from
a thread that doesn't own the level:
* **Ticket changes** are handed to the owner's task queue, so they apply a moment later
  instead of racing.
* **Everything else** is counted as `diag:foreign_chunk_access:<site>` (in the stats file
  and `/mtmc status`) and logged at ERROR with the caller's stack trace, the first 5 times per
  site.

`/mtmc selftest` exercises it on purpose: each worker adds a ticket in the other levels.

**Rule for other mods:** anything that runs inside a level's tick runs on that level's
worker, in parallel with the other levels. That includes Fabric `ServerTickEvents.*_WORLD_TICK`,
`ServerChunkEvents` load and unload, and mixins into `ServerLevel.tick`. Such code must not
touch other levels or unsynchronised global state. The guard names the code that touches
another level's chunk system.

## Compatibility

* **Moonrise:** declared `breaks`. It overwrites `getChunk`, `getChunkNow` and
  `getChunkFutureMainThread`, and asserts its own tick thread (`TickThread.ensureTickThread`).
  Supporting it would mean making the workers Moonrise tick threads, and its chunk scheduler
  accepting several levels being ticked at once.
* **Lithium:** `world.chunk_access` overwrites `getChunk`. Its off-thread branch still compares
  against the same `mainThread` field and hands off with `supplyAsync(...).join()`, so ownership
  works unchanged. Only a hook on the vanilla `join` call would miss, which is why the hook is
  at the method head.

## Explosions

**v0.1.** An explosion runs entirely on its level's worker, in vanilla order:
1. rays and the block list
2. entity damage and knockback
3. block removal and drops
4. neighbour updates
5. the explosion packet

Nothing else touches that level during the phase, and the server thread doesn't run then.
`lab/tnt_test.py` checks this on all three levels exploding on the same tick: drops equal
destroyed blocks, chains use up every TNT, a deterministic cannon gives the same fingerprint
as vanilla, and the client sees the same blocks as the server.

**Rules for in-level regions (not built yet):**
* **Buffer by explosion reach.** A ray travels at most about 1.7 × power blocks, and entities
  are affected out to 2 × power. TNT is 4, beds and anchors 5, crystals and charged creepers
  6, so about 16 blocks covers normal play.
* **Oversized or boundary-crossing explosions run alone.** A summoned fireball can be power
  127. An explosion whose reach crosses its region's edge is deferred to after the phase, as
  cross-dimension work is now.
* **Primed TNT is a moving entity.** Chains fling it; when it leaves its region it moves over
  at the phase boundary, or the regions merge (Folia).
* **The cannon fingerprint stays the regression test** for any change to tick order.

## Parallel sensor phase (`ai/SensorPhase`, since 2026-10-02, off by default)

Inside one level, before the entity tick loop, the due brain sensors of all mobs run on a
pool while the level's thread runs a share and then waits. Each mob's brain tick skips the
sensors that already ran. Only whitelisted sensors run early, per mob in the brain's order
up to the first non-whitelisted one.

**Thread safety:**
* Static `TargetingConditions` become thread-local on sensor threads.
* Chunk reads are read-only lookups of loaded chunks. A miss falls back to the brain tick.
* Entity sections' lazy by-class cache is locked while a phase runs.

Full write-up and lab results: AI_OFFLOAD.md, LAB_RESULTS.md.

## Known limits and open items

* **Gain only where there is work in more than one dimension.** One busy Overworld gets
  nothing. Entity or region parallelism inside a level is the next step (see RESEARCH.md).
* **Scoreboards and advancements are not synchronized.** A kill in one dimension and a
  criterion in another at the same instant could race on `ServerScoreboard`'s maps. These are
  rare (players tick on the server thread), but not proven impossible. A lab scenario should
  target them.
* **Global broadcasts** (`PlayerList.broadcastAll`, e.g. the dragon's death sound) can send to
  a player from another level's worker. Netty's `Channel.write` is thread safe, so only packet
  order between two dimensions could differ.
* **Other mods** that keep static mutable state touched during a level tick are not thread
  safe under this mod. Async (parallel entities) and this mod both wrap level ticking and have
  not been tried together.
* **Watchdog:** a stuck worker shows up as the server thread waiting in `runCollected`.

## Files

| File | What it does |
| --- | --- |
| `ParallelLevelTicker` | The phase: grouping, ownership swap, help-while-waiting, deferral queue |
| `MtmcConfig` | `config/multithreadmc.properties` |
| `MtmcCommand` | `/mtmc` |
| `MtmcStats` | The `[stats]` log line and `mtmc-stats.json` |
| `mixin/MinecraftServerMixin` | Collect the levels, then run the phase |
| `mixin/ServerLevelMixin` | Hoisted time, deferred wake-up |
| `mixin/ServerChunkCacheMixin` | Help-while-waiting |
| `mixin/EntityMixin`, `ServerPlayerMixin`, `ThrownEnderpearlMixin` | Deferred portals, teleports and pearls |
| `mixin/CommandBlockMixin`, `BaseCommandBlockMixin` | Deferred commands |
| `mixin/SavedDataStorageMixin` | Synchronized store |
| `mixin/CommandsMixin` | Registers `/mtmc` (no Fabric API needed) |
| `ai/SensorPhase`, `ai/SensorAccess` | Parallel sensor phase: job collection, pool, fallback, thread-local targeting conditions |
| `mixin/SensorMixin` | Skip sensors already run this tick; thread-local `TargetingConditions` |
| `mixin/ClassInstanceMultiMapMixin` | Lock entity-section class lookups while a sensor phase runs |
| `mixin/BrainAccessor`, `ServerLevelTickListAccessor`, `ServerChunkCacheInvoker` | Accessors for the sensor phase |
