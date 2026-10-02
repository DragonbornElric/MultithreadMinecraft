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

## Compatibility

* **Moonrise:** declared `breaks`. It overwrites `getChunk`, `getChunkNow` and
  `getChunkFutureMainThread`, and asserts its own tick thread (`TickThread.ensureTickThread`).
  Supporting it would mean making the workers Moonrise tick threads, and its chunk scheduler
  accepting several levels being ticked at once.
* **Lithium:** `world.chunk_access` overwrites `getChunk`. Its off-thread branch still compares
  against the same `mainThread` field and hands off with `supplyAsync(...).join()`, so ownership
  works unchanged. Only a hook on the vanilla `join` call would miss, which is why the hook is
  at the method head.

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
