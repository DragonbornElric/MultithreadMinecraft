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

## Open items

* Rerun `nether_hero` for the second mtmc session (the worker restart cut it off).
* Run each correctness scenario 5× per variant for counts, not single runs.
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
