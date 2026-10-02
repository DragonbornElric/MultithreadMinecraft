# MultithreadMinecraft

A server-side Fabric mod for Minecraft **26.2** that ticks the server's dimensions in parallel,
one worker thread per dimension (or a configured number of threads). It also includes a lab to
benchmark it and stress-test it with a real bot.

It uses the concept of [MCMT / MCMTFabric](https://modrinth.com/mod/mcmtfabric) (parallel
"world" ticking), written from scratch against 26.2's unobfuscated code, not ported. Unlike
MCMT, it only does the part that keeps vanilla behaviour inside each dimension:
* entities, redstone, block entities and scheduled ticks keep vanilla order within their
  dimension
* the few things that cross dimensions (portals, cross-dimension teleports and ender pearls,
  command blocks) run right after the parallel phase, on the server thread, within the same
  tick

* [docs/RESEARCH.md](docs/RESEARCH.md): the options (MCMT, Async, C2ME, Folia, …), what is
  safe to parallelise, and what this project does next
* [docs/DESIGN.md](docs/DESIGN.md): how the mod works and its known limits
* [lab/README.md](lab/README.md): the benchmark and the bot stress run
* [docs/AI_OFFLOAD.md](docs/AI_OFFLOAD.md): mob pathfinding, brains and sensors off the main thread: how 26.2 does it, profiles, options
* [docs/LAG_MACHINES.md](docs/LAG_MACHINES.md): throttling lag machines: research and a proposed design

## Use

Build it:

```bash
cd mod && JAVA_HOME=<jdk 25> ./gradlew build
```

The jar is `mod/build/libs/multithreadmc-<ver>+26.2.jar`. It goes in a Fabric 26.2 server's
`mods/` (loader 0.19.5+; no Fabric API needed). The client doesn't need it.

`config/multithreadmc.properties`:

| Key | Default | Meaning |
| --- | --- | --- |
| `parallelDimensions` | `true` | Off = vanilla level loop. The mod then only adds a stats line. |
| `threads` | `0` | Worker threads. `0` = one per dimension (3 on a vanilla world). Fewer than the number of dimensions: dimensions are shared out, busiest first. Below 2 = vanilla. |
| `deferCommandBlocks` | `true` | Run command blocks after the parallel phase (a command can reach any dimension). |
| `logCrossLevelAccess` | `true` | Log a stack trace the first time each kind of cross-dimension chunk access happens. |
| `statsIntervalSeconds` | `30` | How often the `[stats]` log line and `mtmc-stats.json` are written. |
| `sensorPhase` | `false` | Parallel sensor phase: due mob brain sensors (whitelisted ones) run on a pool before entity ticking. See [docs/AI_OFFLOAD.md](docs/AI_OFFLOAD.md). |
| `sensorThreads` | `0` | Sensor pool size. `0` = CPUs − 1. |
| `sensorPhaseMin` | `32` | Fewer mobs with due sensors than this in a level tick: run them the vanilla way. |

The `/mtmc` command (op level 3) changes these live, from the next tick, and saves them:

```
/mtmc status          mode, threads, last stats window
/mtmc on | off        toggle parallel dimension ticking
/mtmc threads <n>     0 = one per dimension
/mtmc selftest        for 20 ticks, every worker loads chunks in the other dimensions
                      (the cross-dimension path); /mtmc selftest result to read it
/mtmc lag [top N]     worst chunks by tick time (5 s average), by category; hover = contents, click = teleport
/mtmc lag here        the chunk you stand in
/mtmc lag on|off|reset              lag accounting (on by default)
/mtmc lag throttle [on|off]         slow chunks over budget (off by default)
/mtmc lag throttle budget <ms>      per-chunk budget (default 2 ms/t)
/mtmc lag throttle busy <ms>        throttle only while server MSPT >= this (default 40)...
/mtmc lag throttle hard <ms>        ...or always when a chunk alone is over this (default 10)
/mtmc lag throttle freeze <ms>      freeze a chunk still over this at the slowest level (default 20)
/mtmc lag throttle light <n>        light updates per tick a chunk may queue (default 2000)
/mtmc lag release                   all slowed and frozen chunks back to full speed
/mtmc lag caps [on|off]             per-chunk caps for new entities (off by default), and what they did so far
/mtmc sensors [on|off]              parallel sensor phase (off by default), and what it did so far
/mtmc sensors threads <n>           sensor pool size (0 = CPUs - 1)
/mtmc sensors min <n>               fewest mobs with due sensors per level tick to use the pool (default 32)
```

### Lag machines: accounting and throttle

* **Accounting:** every chunk's tick time is measured by category:
  * entities
  * block entities
  * scheduled block ticks
  * fluid ticks
  * block events
  * random ticks

  Work started by a tick is counted with it, so a redstone chain is charged to the clock that
  drives it. Overhead in the lab: within noise, at most about 4% with 3000 mobs.
* **Throttle (opt-in):** a chunk that stays over budget is **slowed, not cancelled**. It ticks
  on 1 in 2, 4, … 32 game ticks. On the other ticks it is "not ticking", the state vanilla gives
  chunks just outside simulation distance:
  * scheduled ticks wait in order
  * block events are rescheduled
  * block entities, entities (never players) and random ticks skip

  Clocks run slower; nothing is half-moved, lost or duplicated (lab: 64/64 items through a
  throttled hopper line, 300/300 minecarts).
* **When it engages:** only while the server is busy (smoothed MSPT ≥ `lagServerBusyMs`, default
  40), or always when one chunk alone is over `lagHardBudgetMs` (default 10 ms). It eases off on
  its own. Ops get a chat notice with a teleport link.
* **Light updates:** counted per chunk too, because light is computed off the tick thread and
  never shows in tick time. A chunk over `lagLightBudget` updates per tick counts as over
  budget for the throttle; slowing it slows the redstone flipping the lamps.
* **Freeze, the last resort:** a chunk still over `lagFreezeMs` (default 20) at the slowest
  level (1 in 32, i.e. ~640 ms/t at full speed) for 5 s stops ticking entirely until
  `/mtmc lag release`. Ops are alerted. Players in it still tick.
* **Entity caps (opt-in, `lagCaps`):** per chunk, for **new** entities only. Saved entities and
  portal arrivals are never touched.

  | Category | Cap | At the cap |
  | --- | --- | --- |
  | Items | `capItems` 400 | merge into a stack with room, else wait |
  | Primed TNT | `capTnt` 300 | wait, then explode |
  | Falling blocks | `capFallingBlocks` 200 | wait, then fall |
  | Minecarts and boats | `capVehicles` 64 | refused and dropped as their item |
  | Armor stands | `capArmorStands` 64 | refused and dropped as their item |
  | Mobs | `capMobs` 300 | refused |

  Waiting entities join the world before every save, so nothing is lost.
* **Settings:** `lagAccounting`, `lagThrottle`, `lagChunkBudgetMs`, `lagServerBusyMs`,
  `lagHardBudgetMs`, `lagMaxThrottle`, `lagLightBudget`, `lagFreeze`, `lagFreezeMs`,
  `lagCaps`, `cap*` in `config/multithreadmc.properties`. Design and research:
  [docs/LAG_MACHINES.md](docs/LAG_MACHINES.md).

## Compatibility

| Mod | Status |
| --- | --- |
| Moonrise | **Incompatible.** The loader refuses to start with both (`breaks`). Moonrise replaces the chunk system and only allows chunk scheduling from its own tick thread (`TickThread.ensureTickThread`), which the dimension workers are not. |
| Lithium 0.25.3 (default and `mixin.experimental=true`) | Works. Lithium replaces `ServerChunkCache.getChunk` (`world.chunk_access`); since 2026-10-02, MultithreadMC hooks the method head instead of a call inside it. Tested in the lab: boot, `/mtmc selftest`, benchmark, bot scenarios. |
| ServerCore 1.5.19 (`dynamic` on) | Works. Tested together with Lithium as above. |
| PathWeaver 0.9.0 (async pathfinding) | Boots and works together with MultithreadMC (selftest, chase profile). TNT and bot suites not yet run with it. |
| C2ME, Async, others | Not tested. C2ME also replaces large parts of the chunk system. |

To test a stack in the lab: `lab/fetch_mods.sh <modrinth slugs>`, then use a variant like
`mtmc+lithium+servercore` (see `lab/README.md`).

## Results so far (lab, 4 vCPU, 26.2)

1000 animals in a force-loaded pen in each of the three dimensions. MSPT is the average tick
work time over four `/tick query` samples at 20 TPS. Sprint TPS is from `/tick sprint 1200`.

| Variant | MSPT | Sprint TPS |
| --- | --- | --- |
| vanilla | 51.5 | 19 |
| mtmc, 2 threads | 36.6 | 27 |
| mtmc, 3 threads (default) | 22.2 – 24.0 | 46 – 47 |
| mtmc loaded, parallel off | 53.9 | 19 |

The gain depends on how the load is spread: all the work in one dimension means no gain.
Inside one dimension, everything still ticks on one thread; that is the next step (see
RESEARCH.md).

Full log, including the vanilla baseline for every scenario: [docs/LAB_RESULTS.md](docs/LAB_RESULTS.md).

Bot stress run (EmmaBot, the Emma bridge bot on 26.2), with the pens loaded in all three
dimensions; no exceptions, deadlocks or crashes:
* `command_blocks`: 600/600 runs per block across dimensions
* `portal_stream`: 200/200 entities through a portal
* `pearl`: 6/6 cross-dimension ender pearls
* `portal_walk`: 4/4 portal trips by the bot
* the bot playing hero mode for 3 minutes in the Overworld and 3 in the Nether
