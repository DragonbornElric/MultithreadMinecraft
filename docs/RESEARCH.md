# Multithreading a Minecraft 26.x server: options

Checked on 2026-10-01 against the Modrinth API, the mods' source repositories, and the
decompiled 26.2 server jar (`server-26.2.jar`, unobfuscated, read with Vineflower).

## What vanilla 26.2 does on one thread

`MinecraftServer.tickChildren` runs, in order: command functions, clocks, then **each level in
turn** (`for (ServerLevel level : getAllLevels()) level.tick(haveTime)`), then connections, the
player list and chunk sending. `ServerLevel.tick` does the world border, weather, sleep, time,
scheduled block and fluid ticks, raids, the chunk source (random ticks, spawning, entity
tracking, chunk sending), block events, entities, block entities and entity management, in that
order.

Vanilla already uses other threads for:
* chunk generation, loading and saving (the worker pool, `IOWorker`)
* lighting (`ThreadedLevelLightEngine`, which runs on its own task queue)
* networking (Netty)

What stays on the server thread is game logic.

Vanilla also already fences each level off by thread. These are the only two thread checks on
`Level`/`ServerChunkCache` in 26.2:

| Field | Effect off that thread |
| --- | --- |
| `Level.thread` | `getBlockEntity` returns `null` |
| `ServerChunkCache.mainThread` | `getChunk` hands the request to that thread's task queue and joins; `getChunkNow` returns `null` |

## MCMT and MCMTFabric

* **JMT-MCMT** (jediminer543, Forge 1.15/1.16, BSD-2-Clause, last commit 2022-08):
  https://github.com/jediminer543/JMT-MCMT
* **MCMTFabric** (himekifee, GPL-3.0-only; Modrinth build only for 1.19.3, GitHub branches up
  to 1.21.1, Yarn mappings): https://github.com/himekifee/MCMTFabric, https://modrinth.com/mod/mcmtfabric

**What it parallelises.** It runs four loops of the server tick on a thread pool, each with
its own switch:
* worlds (dimensions)
* entities
* block entities
* environment (chunk) ticks

The README credits most of the gain to entities.

**How.**
* Mixins redirect each tick call into an executor and join with a `Phaser`.
* Safety comes from:
  * `ACC_SYNCHRONIZED` added to every method of a list of vanilla classes (path heap, tick
    schedulers, light propagator, navigation, random, criteria, world border)
  * about a dozen hand-written concurrent fastutil replacements
  * a chunk lock
  * per-entity patches
  * block entity whitelists and blacklists

**Track record.** From its issue tracker:
* Lithium incompatibility
* watchdog crashes and deadlocks
* "Exception in server tick loop"
* the dragon not respawning
* broken spawn rates and raid farms

Most were closed as stale. The original README says *"It might break simply by you looking at
it"*.

**26.x.** There is no 26.x build. Every mixin target is a Yarn name, so a port is a rewrite.

**Licence.** MCMTFabric's code can only be copied into a GPL-3.0 project. JMT-MCMT's
(BSD-2-Clause) can be copied anywhere as long as the notice is kept, but it is Forge 1.16 ASM.
**This project reuses the concept only, written clean-room against 26.2.**

## Mods that already run on 26.2 (Modrinth, newest 26.2 build)

| Mod | Threads what | 26.2 build | Licence | Notes |
| --- | --- | --- | --- | --- |
| [Async](https://github.com/AxalotLDev/Async) | Entity ticking (batches on a ForkJoinPool) | 0.2.4+alpha-26.2 (2026-07-28) | GPL-3.0+ | Alpha. TNT, items and XP orbs stay serial. Async spawning and random ticks are opt-in. Open issues: deadlocks, a hopper crash, removed entities still ticking. Incompatible with Moonrise. |
| [C2ME](https://github.com/RelativityMC/C2ME-fabric) | Chunk generation, I/O, serialisation, lighting | 0.4.2-alpha.0.55+26.2 | MIT | Keeps vanilla parity. Incompatible with Moonrise. |
| [Moonrise](https://github.com/Tuinity/Moonrise) | None (Paper's chunk system plus entity and collision optimisations) | 1.1.2 | GPL-3.0 | Single-thread speedups |
| [ScalableLux](https://modrinth.com/mod/scalablelux) | Lighting (Starlight successor, parallel updates) | 0.3.0-alpha+26.2 | LGPL-3.0 | |
| [Lithium](https://modrinth.com/mod/lithium) | None (game-logic optimisation) | mc26.2-0.25.3 | LGPL-3.0 | |
| [VMP](https://modrinth.com/mod/vmp-fabric) | None (many-player scaling) | 0.2.0+beta.7.236+26.2 | MIT | |
| [Krypton](https://modrinth.com/mod/krypton) | Network stack | 0.3.1 | LGPL-3.0 | |
| [Folia](https://github.com/PaperMC/Folia) | Everything, as independent regions on a pool | `ver/26.2.x` branch | GPL-3.0 | Paper fork, not Fabric. Plugins need region schedulers. Production-proven (the Emma servers already run Folia plugins). |

## What can and can't run in parallel

**Order-dependent, keep serial (or partition by region the way Folia does):**
* redstone and neighbour updates
* scheduled block and fluid ticks
* block events
* entity interactions: pushing, hoppers and item pickup (dupes), breeding, raids, spawning
  caps
* shared random sources
* advancements and scoreboards
* random ticks that cascade into neighbour updates

**Safe offload targets:**
* chunk generation, I/O and serialisation (vanilla, C2ME)
* lighting (vanilla's queue, ScalableLux)
* network compression and encryption (Krypton)
* pathfinding on a block snapshot
* sensor scans
* entity tracking and visibility
* POI lookups
* saving

The pattern that holds up is to compute in parallel on read-only snapshots and apply changes
serially. The only model where everything runs in parallel safely is regions with no shared
mutable state.

## What this project does (and why)

1. **Dimensions in parallel (done, `mod/`).** This is MCMT's "worlds" switch. It is the safest
   of MCMT's four switches: different levels share almost no mutable state, and vanilla's two
   thread fields already make each level single-owner. Each worker takes ownership of its
   level for the phase. The few cross-level paths are handled on purpose (see
   [DESIGN.md](DESIGN.md)).
   * **Gain:** only when more than one dimension has work. A server with players spread over
     the Overworld, Nether and End gets up to ~3× on the level phase. A server where all the
     load is in the Overworld gets nothing.
2. **Entity ticking in parallel, in-level (not done).** This is where MCMT and Async get their
   numbers, and also where all their bugs live. A sound version is Folia's idea: partition by
   region (groups of chunks far enough apart that no entity can touch another group within a
   tick) and tick regions in parallel. Within a region, order stays vanilla.
3. **Offloading (candidates).** Each one computes off-thread and applies on-thread:
   * pathfinding (`PathFinder.findPath` on a `PathNavigationRegion` snapshot)
   * the entity tracker (`ChunkMap.tick` → `TrackedEntity.updatePlayers`)
   * sensors (`NearestLivingEntitySensor`)

   These are measurable in the same lab.

Unobfuscated 26.x helps here: mixin targets are the real names, a decompile reads like
source, and there is no mapping layer to keep in sync.
