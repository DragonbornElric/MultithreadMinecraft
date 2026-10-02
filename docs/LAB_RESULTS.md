# Lab results

The running log of every benchmark and stress run. Newest sections go at the top of each part.
Raw data: `$LAB_DIR/results.jsonl` (benchmarks) and `$LAB_DIR/stress.jsonl` (bot runs). Since
2026-10-02 every benchmark line records the host CPU (`host`). Set `LAB_HOST=<name>` to label
your machine.

## Machines

| Label | CPU | Logical CPUs | Notes |
| --- | --- | --- | --- |
| `cloud-4` | Intel Xeon @ 2.10GHz (cloud VM) | 4 | Where all results below were taken. Server, bot client (software GL) and scripts share the 4 vCPUs. |
| `owner-pc` | (owner's local PC) | 12 | Owner's local benchmark machine. |
| `owner-server` | (owner's server) | 32 threads / 16 cores | Owner's target host. |
| `5950x` | AMD Ryzen 9 5950X | 32 (16C) | Owner: test server and bot host |
| `9950x3d` | AMD Ryzen 9 9950X3D | 32 (16C) | Owner: test server and bot host |
| `ai-max-395` | AMD Ryzen AI Max+ 395 | 32 (16C) | Owner: test server and bot host |

The owner plans swarm tests of up to 20 bots, joining one per minute, recorded with
`lab/monitor.py` (`--ab` for on/off on the same load).

**What v0.1 can use.** v0.1 runs one worker per dimension. A vanilla world therefore uses
at most 3 threads for the level phase, whatever the machine has. On 12 or 32 threads, v0.1
speeds up the same cases as on 4 cores, no more: more than one dimension has to be busy.
Using 12–32 threads needs parallelism inside a dimension (regions, see RESEARCH.md and
"Next" below).

## Benchmarks (`lab/bench.py`)

Setup for every run:
* fresh world, seed `mtmc-lab`
* an 80×80 force-loaded glass pen at y=200 in each dimension
* cow, sheep, chicken and pig, persistent
* no cramming, no natural spawning

What is measured:
* **MSPT:** the mean of four `/tick query` averages at 20 TPS
* **Sprint TPS:** `/tick sprint 1200`

### 2026-10-01: balanced load, 1000 mobs in each dimension (`cloud-4`)

| Variant | Threads | MSPT avg | MSPT p95 | Sprint TPS | Overlap* |
| --- | --- | --- | --- | --- | --- |
| vanilla | 1 | 51.5 | 65.3 | 19 | — |
| mtmc-off (mod loaded, parallel off) | 1 | 53.9 | 97.1 | 19 | — |
| mtmc@2 | 2 | 36.6 | 50.5 | 27 | 1.48 |
| mtmc | 3 | 22.2 | 30.3 | 46 | — |
| mtmc (repeat) | 3 | 24.0 | 43.2 | 47 | 2.66 |

\* Overlap is the sum of the dimensions' tick times divided by the parallel phase's wall time.
3.0 would be perfect for three equal dimensions. The rest goes to the slowest dimension,
thread hand-off and the deferred queue.

**Reading.**
* About 2.2× on the level phase with three equally busy dimensions on 4 vCPUs.
* Two threads land where expected: three equal jobs on two workers take two jobs' time.
* With the mod loaded but off, sprint TPS and average MSPT match vanilla. The p95 of 97 ms
  is a single run and wasn't reproduced; rerun before drawing anything from it.

### 2026-10-02: Overworld-heavy, 1500 mobs in the Overworld, 150 in each other dimension (`cloud-4`)

Three pairs of runs. Mob counts were checked: 1500 / 150 / 150.

| Variant | Sprint MSPT (3 runs) | Mean | Sprint TPS | 20 TPS samples (avg per sample, ms) |
| --- | --- | --- | --- | --- |
| vanilla | 36.4, 39.9, 40.4 | 38.9 | 27, 25, 24 | 37–39; 89, 44, 52, 58, 44, 43; 105, 71, 51, 50, 45, 43 |
| mtmc (3 threads) | 36.9, 40.1, 43.9 | 40.3 | 27, 24, 22 | 34, 61, 87, 35; 41, 40, 50, 45, 41, 60; 94, 44, 47, 51, 64, 53 |

**Reading.**
* **No measurable gain.** The Overworld's tick (about 36–38 ms) is almost the whole phase.
  The Nether and End take about 3 ms each, which the mod overlaps (`overlap` 1.14–1.16). In
  theory that saves about 6 ms a tick, but it doesn't show above run-to-run noise. The mean
  sprint figure is 1.4 ms (≈3.5%) worse with the mod; the runs overlap, so this doesn't settle
  whether there is a small cost.
* **The 20 TPS spikes are not the mod.** Both variants spike in the first samples after the
  summon (vanilla up to 105 ms) while 1500 mobs settle into the pen. The mod's stats put the
  extra time inside the Overworld's own tick, not in the phase overhead. For this load, the
  sprint figure is the one to trust.
* **The expected case for v0.1.** One busy dimension can't go faster with dimension-level
  threads. This is the limit the region step is meant to remove.

### 2026-10-02: owner stack, balanced load, 1000 mobs in each dimension (`cloud-4`)

The stack is `lab/configs/owner-stack`:
* Lithium 0.25.3 with `mixin.experimental=true`
* ServerCore 1.5.19 with `dynamic.enabled: true`
* Fabric API 0.161.0
* view 12, simulation 8, ZGC
* 6 GB heap (the owner uses 20 GB)

| Variant | MSPT avg | MSPT p95 | Sprint TPS |
| --- | --- | --- | --- |
| vanilla+lithium+servercore | 11.7 | 16.0 | 81 |
| mtmc+lithium+servercore | 5.8 | 8.4 | 172 |

**Reading.**
* Lithium (with ServerCore) cuts this load from 51.5 to 11.7 ms. MultithreadMC halves what is
  left. The two stack: Lithium makes each dimension's tick cheaper, and MultithreadMC runs
  the dimensions at the same time.
* ServerCore's dynamic mode never had to step in: its MSPT target is 35.
* Single runs; repeat them before treating the exact ratio as settled.

## Compatibility checks (2026-10-02, `cloud-4`)

| Stack | Boot | `/mtmc selftest` (cross-dimension chunk loads from workers) | Bot scenarios |
| --- | --- | --- | --- |
| mtmc | ok | 120/120, no deadlock | see below |
| mtmc + Lithium (experimental) + ServerCore (dynamic) | ok, after the getChunk hook moved to the method head. Before, it crashed on boot: Lithium's `world.chunk_access` overwrites `getChunk`. | 120/120 | command_blocks 610/610 ticks, portal_stream 200/200, pearl 6/6, portal_walk 4/4 |
| mtmc + Moonrise | refused by the loader (`breaks`), on purpose | — | — |

The owner hit the Moonrise conflict first, on their own server.

## Bot stress and correctness (`lab/stress_bot.py`)

The bot is EmmaBot: the Emma bridge bot, EmmaMinecraft261 branch `mc-26.2`, unmodified, under
Xvfb. Every scenario runs with 600 mobs in a pen in each dimension. "Log problems" means
exceptions or "Can't keep up" lines in the server log during the scenario.

### 2026-10-02: mtmc (3 threads) vs vanilla, same scenarios (`cloud-4`)

| Scenario | What it checks | vanilla | mtmc |
| --- | --- | --- | --- |
| `command_blocks` | Repeating command blocks in the Nether and End writing into another dimension every tick (deferred) | pass, 600/600 runs in 600 ticks | pass, 567/567 in 567 ticks (600/600 in an earlier run) |
| `portal_stream` | 200 chickens through an Overworld portal (deferred entity portals) | pass, 200/200 | pass, 200/200 (twice); one earlier run 199/200, see below |
| `pearl` | Pearl lands in one dimension, owner in the other (deferred landing) | pass, 6/6 | pass, 6/6 (twice) |
| `portal_walk` | The bot walks through a portal and back | pass, 4/4 | pass, 4/4 (twice) |
| `hero` (Overworld) | The bot plays: mining, combat, exploring, chunk generation | pass, MSPT 37.9 | pass, MSPT 26.2 (33.6 in the first run) |
| `nether_hero` | The same in the Nether | pass, MSPT 38.6 | pass, MSPT 26.8 (first run) |

**Errors.** No exceptions, deadlocks or watchdog kills in any run, with or without the mod.
The only log lines were a few "Can't keep up" warnings right after the bot joined, during
both vanilla and mtmc runs. In the mtmc runs, the mod's `cross_level` counter stayed at 0:
no worker ever had to load a chunk in another dimension.

**Deferred work in one mtmc session:**
* about 3800 command-block runs
* 1004 entity portal uses
* 6 ender pearl landings

**Players and portals.** The `portal` counter did not move during the bot's own portal
trips. Players use portals in their own tick, which vanilla runs from the connection tick on
the server thread, outside the parallel phase.

**Earlier failures, all caused by the test script, not the mod:**
* `command_blocks` read the scores over several ticks: off by 1–2. Fixed by freezing ticks
  while reading.
* `portal_walk`: the bot kept walking toward a goto target given in the other dimension's
  coordinates. Fixed by stopping it on arrival.
* The `hero` alive check matched the wrong text.
* The 199/200 `portal_stream` happened once with mtmc. The next mtmc run and the vanilla run
  were 200/200. The likely cause is a chicken that fluttered off the portal, but that isn't
  proven. Repeat this scenario several times on both variants to settle it.

## TNT (`lab/tnt_test.py`, 2026-10-02, `cloud-4`)

Every trial runs the same setups in all three dimensions. They are built under `/tick
freeze` and start on the same tick, so with the mod three workers explode at once.

| Check | What it proves | Pass condition |
| --- | --- | --- |
| crater | Blocks get broken and drop | 15³ dirt cube, one TNT in the middle, `tnt_explosion_drop_decay` off: dirt items dropped == blocks destroyed, exactly, in every dimension |
| chain | Chain reactions finish | 300 TNT blocks in a sealed obsidian box: no TNT blocks and no primed TNT left |
| cannon | Effects are identical | On barrier blocks, 8 TNT launch a TNT projectile past three no-AI 200 HP pigs, run with `/tick step 60`. Knockback and damage use no randomness, so the projectile's position and motion and the pigs' health hash must match across the three dimensions and across variants. |
| sync (bot) | Clients stay in sync | The bot's client sees the crater cube (`scan_area`) block for block as the server has it |

| Variant | Trials | crater (drops == destroyed) | Destroyed per crater (mean ± sd, n=15) | chain (all TNT used) | cannon fingerprint | Exceptions |
| --- | --- | --- | --- | --- | --- | --- |
| vanilla | 5 | 15/15 | 225.1 ± 3.2 | 15/15 | `3f99461661c4` | none |
| mtmc | 5 | 15/15 | 225.8 ± 3.1 | 15/15 | `3f99461661c4` | none |
| vanilla+lithium+servercore (owner stack) | 5 | 15/15 | 224.3 ± 4.4 | 15/15 | `3f99461661c4` | none |
| mtmc+lithium+servercore (owner stack) | 5 | 15/15 | 224.1 ± 3.3 | 15/15 | `3f99461661c4` | none |

Client sync, bot in spectator 20 blocks from the Overworld crater, 3 trials each:

| Variant | Client == server, block for block | Dirt blocks compared |
| --- | --- | --- |
| mtmc | 3/3 | 3145, 3148, 3150 |
| vanilla | 3/3 | 3147, 3139, 3151 |

**Reading.**
* **Identical effects.** The cannon fingerprint is the same in all four setups and in all
  three dimensions: projectile position to 14 digits, and damage such as 195.06367 HP. Neither
  the parallel workers nor Lithium's explosion code change any damage or motion.
* **Blocks and drops are exact.** Every destroyed block dropped, in every dimension, with all
  three exploding on the same tick.
* **Crater size is random.** The rays use randomness, so it is compared as a distribution: the
  means are within 1 block of each other.
* **The chain's worst tick isn't a useful comparison.** One 300-TNT chain per dimension, three
  at once, gives about 230–560 ms in the worst `/tick query` sample, scattered between runs
  and variants alike.

**Test bugs found and fixed:**
* The cleanup between trials used `/kill` on the cannon's pigs, and their porkchops were
  counted into the next crater. Trials 1–4 failed the same way on vanilla and on the mod.
  Cleanup now removes items after mobs, and the crater counts only dirt items.
* The first sync runs read the wrong field of the bridge reply (`data.blocks`).

## Mob AI profiles (`lab/profile_ai.py`, 2026-10-02, `cloud-4`)

Full tables and analysis: [AI_OFFLOAD.md](AI_OFFLOAD.md). In short, with 600 mobs, JFR, and
shares of tick-thread CPU:

| Scenario | Vanilla MSPT | Lithium MSPT | Biggest costs |
| --- | --- | --- | --- |
| animals | 20.2 | 8.9 | Movement and collisions (dense pen); pathfinding 1.5–3.4% |
| villagers | 43.3 | 17.7 | Brains 68–74%; POI scans 14% (Lithium: 2%); sensors 5–11% |
| chase (zombies + villagers) | 26.9 | 17.9 | Goals and pathfinding: 12% vanilla, **31% Lithium** |
| piglins/hoglins | 35.2 | 20.1 | Brains 53–57%; **sensors 21–23%** |

PathWeaver (async A*, Fabric 26.2) on chase: vanilla 27.0 → 21.5 MSPT, Lithium 21.3 → 15.7.
Zombies still catch villagers. It boots and works with MultithreadMC (selftest 120/120).

## Lag accounting and throttle (branch `-next`, 2026-10-02, `cloud-4`)

Full design and tables: [LAG_MACHINES.md](LAG_MACHINES.md).

* **Accuracy (`lag_lab.py detect`):** builds were added one at a time, comparing the MSPT each
  added with what `/mtmc lag` charged to its chunk. Every build that added at least 1 ms was
  within ±50%; the minecart stack was within 2% (31.1 vs 30.5). The machines rank on top.
* **Overhead (`lag_lab.py overhead`):** 3000 mobs, accounting on vs off: 32.0 vs 30.9 MSPT
  (3.6%, within noise).
* **Throttle (`lag_lab.py throttle`):** hot chunk next to an identical cool one.
  * MSPT 48.5 → 3.05; the hot chunk was slowed to 1 tick in 16.
  * Hot clock 3.3 → 0.4 pulses/s; cool clock unchanged.
  * 64/64 stone in both hopper lines; 300/300 minecarts.
  * Full speed again after `release`.
* **Regressions on this build:**
  * TNT suite, throttle off: all pass, cannon fingerprint unchanged (`3f99461661c4`).
  * Bot scenarios: command blocks 495/495, pearls, portal trips: pass.
* **TNT with the throttle forced on** (budget, busy and hard gates all near 0): 113 throttle
  changes.
  * Craters: drops == destroyed every trial.
  * Chains: at the usual 12 s check, 256–278 primed TNT were still waiting in slowed chunks.
    60 s later every chain had finished: 0 TNT blocks, 0 primed TNT, in all three dimensions.
    Slowed, not broken.
  * The cannon fingerprint is expected to change under a throttle (entities skip ticks), so it
    was not part of this run.

### Portal stream "199/200" explained (2026-10-02)

The `portal_stream` scenario sometimes counted 1–5 of 200 chickens left in the Overworld. Runs
of 6 per variant: vanilla 199, 200, 200, 199, 200, 200; mtmc 196, 197, 198, 198, 195, 200.

Investigation:
* Every leftover chicken had a portal cooldown: 300, or less if it had stepped out of the
  portal. 0 had cooldown 0, on vanilla and mtmc alike.
* A chicken's portal cooldown is set when it is teleported and carried over to the new
  dimension. So the leftovers had **gone to the Nether and wandered back** through the
  Nether-side portal within the 25 s test window.
* A diagnostic counter for "decided to teleport but didn't" (`diag:portal_no_teleport` in the
  stats) never fired.

Nothing is lost and no entity fails to go through. The test now counts those chickens apart
(`back_in_overworld`) and fails only on `never_went`. The mod had somewhat more round trips in
the first set of runs (2–5 vs 0–2 per 200); in a later paired set both variants had 2. Treat
it as a possible small timing difference in when the portal is processed (end of the tick
instead of the entity's next tick start), not a correctness problem.

## Ownership guard (2026-10-02, `cloud-4`)

* **`/mtmc selftest`:** 120/120 cross-dimension chunk loads, and 120 cross-dimension tickets
  caught and handed to their owner per run. The handed-over tickets took effect: the target
  chunk read as loaded in all three dimensions within about 6 s, including its generation.
* **No false alarms:** 0 `[diag]` lines at startup and world generation, and through each of
  these suites:
  * TNT suite: pass, fingerprint `3f99461661c4`
  * throttle test: pass
  * benchmark at 800 mobs per dimension
  * bot scenarios: command blocks 497/497, portal stream 197 + 3 came back + 0 never went,
    pearls, portal trips, 90 s hero in the Overworld and in the Nether

## Combat across threads (owner question, 2026-10-02)

**Can a player fighting a mob be hurt by them being on different threads?** Not in v0.1:
* A player and every mob that can reach them are in the same dimension, so they tick on the
  same worker in vanilla order.
* The player's own actions (attack and use packets, the player's own tick) run on the server
  thread while the workers are stopped.
* The bot fought mobs in `hero` and `nether_hero` with no errors.

**Rule for in-dimension parallelism (next step):** anything that can interact within a tick
(a player and the mobs near them, hoppers and item entities, redstone) must tick on the same
thread. That means regions of nearby chunks, never "entities on thread A, the player on
thread B".

## Owner stack at scale: bots + passive players (2026-10-02, emmabrain, owner's lab)

Real EESMP stack: be166ba + full Lithium (experimental on) + ServerCore (dynamic sim distance only) + emma-smp 2c8a6d7,
view 12 / sim 8, 48 G ZGC, 9 dimensions, **full vanilla mob spawning** (owner: no mob-cap or activation-range changes).
Load: 25 Emma bridge bots (HeadlessMC on emmaserver, `@hero diamond gamer`, both overworlds) plus SoulFire 2.10.1
passive players ("ghosts", creative, each dropped at a random fresh surface spot, one every 3 s, from the owner's PC).
Scripts and raw notes: EmmaMinecraft261 `tools/smp_stress/` (`scale.py`, `cpu_sampler.py`, `STRESS_NOTES.md`).

**Run A: ghosts on 2 dimensions** (rpg:overworld + anarchy:overworld)

| Players (bots+ghosts) | MSPT | TPS | Entities | Overlap |
|---|---|---|---|---|
| 25+0 | 34 | 20.0 | 6.5k | 1.9 |
| 25+27 | 74 | 17.9 | 10.3k | 1.5 |
| 25+68 | 109 | 10.6 | 18.0k | 1.9 |
| 25+109 | 160 | 7.7 | 24.1k | 1.9 |
| 25+132 | 181 | 5.5 | 26.7k | 1.9 |

**Run B: ghosts rotated over 6 dimensions** (rpg/anarchy × overworld/nether/end)

| Players (bots+ghosts) | MSPT | TPS | Entities | Overlap |
|---|---|---|---|---|
| 25+33 | 52 | 17.0 | 10.5k | 2.6 |
| 25+76 | 66 | 13.7 | 16.8k | 3.4 |
| 25+122 | 93 | 10.9 | 22.9k | 3.8 |
| 25+167 | 110 | 8.6 | 28.8k | 4.25 |
| 25+206 | 121-220 | 5.7 | 33.0k | 4.4 |

Per dimension at 101 players (run B): overworlds 67 / 65 ms, nethers 35 / 36 ms, ends 16 / 16 ms; level phase 68.5 ms.
Run A at 114 players: overworlds 132 / 116 ms, everything else < 3 ms.

* Spreading the same players over more dimensions is worth ~1.7× (run B at 147 players ≈ run A at 93). The level phase
  always equals the slowest dimension: **one overworld on one thread is the cap**.
* emmabrain is never the limit: whole-host CPU 20-50 % busy, load 4-8 of 32 threads, 55-60 GB free. Server JVM
  5-15 cores, 51-54 GB RSS at ~230 players. 25 HeadlessMC bots: 10-16 cores, 50 GB (≈2 GB each). SoulFire:
  ~1.5 cores and 12.6 GB for 206 ghosts.
* Where a busy dimension's tick goes (20 jcmd dumps of the MTMC level threads, run A): ~50 % entity ticking
  (`Mob.aiStep`, `GoalSelector`, `Zombie.tick`, `Mob.checkDespawn`), ~40 % `ServerChunkCache.tickChunks`
  (`NaturalSpawner.createState`, `tickSpawningChunk`, random ticks). Server thread: player move packets and chunk sending,
  never overlapping the workers. Worldgen on Worker-Main threads, off the tick. Each spread-out player adds its own spawn
  area: ~100-150 entities and ~1 ms of tick.
* **This is the case for regions inside a dimension (see Next).** With full mob spawning the per-player cost is mobs, and
  players spread far apart are exactly the independent groups that region ticking would split.

**Crash in run A (open): 60 s watchdog** — `lab/results/eesmp-scale-2026-10-02/crash-2026-10-02_09.18.30-unload-hang.txt`.
Server thread in `ParallelLevelTicker.runCollected:142`; `MTMC Level Thread #2` RUNNABLE in
`Long2ObjectLinkedOpenHashMap.remove:733` ← `ChunkMap.lambda$scheduleUnload$0:553` ← `ChunkMap.processUnloads:507` ←
`ServerLevel.tick` ← `tickSerial:192`. Ticks before it: 0.6 → 0.9 → 1.4 → 13.7 s; ZGC cycles 1.4-14 s; heap 36.7 / 48 G
with 132 ghosts loading fresh view-12 areas in two dimensions.
* Vanilla runs the unload callback via `unloadQueue::add` and polls it on the level's own thread, and the server thread
  never handled packets during the parallel phase in any dump, so neither is an obvious race.
* **Not reproduced by a mass disconnect:** at the end of run B all 206 ghosts were dropped at once with mtmc on and the
  tick went straight back to ~32 ms with no stall. So the plain "vanilla unload backlog" explanation is unlikely.
* Remaining suspects: a concurrent write to `pendingUnloads` / `updatingChunkMap` (e.g. a ticket change or chunk load
  reaching another level's `ChunkMap` off its owner thread — something to check with a thread-owner assert in
  `ChunkMap.updateChunkScheduling` / `processUnloads`), or GC pressure near the heap limit. A fastutil open-hash `remove`
  that doesn't return is the classic sign of the first.
* **Follow-up (2026-10-02, branch `-next`, then merged): ownership guard added** (DESIGN.md, "Ownership guard").
  * **Review:** every path MultithreadMC itself takes into a level's chunk system is owner-only:
    * the level tick
    * the help loop (own levels only)
    * cross-dimension `getChunk` (through the owner's queue)
    * deferred work (after ownership is back)
    * ServerCore's dynamic distance (its end-of-tick event on the server thread)
  * **What's left:** code from other mods that runs inside a level's tick, which is on that
    level's worker under MultithreadMC (world-tick and chunk events, mixins), or a mod thread
    touching tickets or chunks directly.
  * **The guard** hands foreign ticket changes to the owner and logs any other foreign access
    with the caller's stack.
  * **In the lab** it is silent in all suites (see "Ownership guard" below), and `/mtmc selftest`
    shows it catching and handing over 120 cross-level tickets per run.
  * **On the next owner run,** any `[diag] foreign_chunk_access` line in the log names the
    culprit.

## Open items

* Rerun `nether_hero` for the second mtmc session (the worker restart cut it off).
* Run each correctness scenario 5× per variant for counts, not single runs.
* Swarm runs on the owner's three 16-core PCs with `monitor.py --ab`, bots spread over the
  dimensions.
* Run the benchmark on the owner's 12-thread PC and 32-thread server (`LAB_HOST=owner-pc
  python lab/bench.py --variants vanilla mtmc mtmc@2 --mobs 1000`). The scripts are bash and
  Python: on Windows, use WSL.
* The Async mod (parallel entities, GPL) as a comparison variant: `run_server.sh async` with
  its jar in `$LAB_DIR/jars/`.
* Scoreboards and advancements under real cross-dimension contention (see DESIGN.md).

## Next

The step after v0.1 is regions inside a dimension: groups of chunks with a buffer wide enough
that nothing in one group can touch another within a tick, each group ticked whole on one
thread. That is what can use 12–32 threads, and the bench's single-dimension (`--dims
overworld`) runs will measure it.
