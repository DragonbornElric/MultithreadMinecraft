# Mob AI off the main thread: pathfinding, brains, sensors (26.2)

Investigation, 2026-10-02. Sources:
* the decompiled 26.2 server jar
* JFR profiles from the lab (`lab/profile_ai.py`)
* the source of the projects that have tried this (research agent, read from source where
  marked verified)

## How 26.2 runs mob AI (read from the decompiled jar)

Each mob's AI runs inside its own entity tick (`Mob.serverAiStep`), on the level's tick
thread:

* **Goal-selector mobs** (zombies, animals, …): `GoalSelector.tick` picks and ticks goals;
  goals ask `PathNavigation` for paths.
* **Brain mobs** (villagers, piglins, hoglins, wardens, axolotls, frogs, goats, allays, …):
  `Brain.tick` does four things in order:
  1. `forgetOutdatedMemories`
  2. `tickSensors`: each `Sensor` runs every `scanRate` ticks, with a random start offset
  3. `startEachNonRunningBehavior`
  4. `tickEachRunningBehavior`
* **Sensors** are read-then-write. `NearestLivingEntitySensor`, for example, queries
  `level.getEntitiesOfClass` in follow range, sorts by distance and writes two memories. They
  read the world and write only their own mob's memories.
* **Behaviours** change the world directly: opening doors, claiming POIs (`AcquirePoi`),
  setting walk targets, attacking.
* **Pathfinding** (`PathNavigation.createPath`) builds a `PathNavigationRegion` and calls
  `PathFinder.findPath` synchronously.
  * The region holds **references to the live chunks** (`getChunkNow`), not a copy.
  * The node evaluator reads the mob's live state: position, bounding box, `onGround`,
    `isInWater`, and the `getPathfindingMalus` map, which goals change.
  * It reads and writes the level-wide `PathTypeCache` through `PathfindingContext`.
  * At the end it calls back into the mob (`mob.onPathfindingDone()`).
  * Path following (`PathNavigation.tick`) runs every tick; recomputing is rate-limited to
    once per 20 ticks per mob.

## Where the time actually goes (lab, 600 mobs in an 80×80 pen, 30 s JFR, `cloud-4`)

Shares are of tick-thread CPU samples and are inclusive: pathfinding is inside goals or
brains, which are inside the AI step. MSPT was taken during the recording.

| Scenario | Variant | MSPT | AI step | Goals | Brains | Sensors | **Pathfinding** | POI | Movement | Collisions |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| animals (cow/sheep/pig/chicken) | vanilla | 20.2 | 7.7 | 5.6 | – | – | **1.5** | – | 54.1 | 36.0 |
|  | +Lithium | 8.9 | 22.0 | 16.6 | – | – | **3.4** | – | 20.8 | 19.4 |
| villagers (no beds/workstations) | vanilla | 43.3 | 75.3 | – | 74.1 | 5.4 | **0.0–0.8** | 14.0 | 14.6 | 12.6 |
|  | +Lithium | 17.7 | 70.7 | – | 68.4 | 11.4 | **1.1** | 2.3 | 6.6 | 13.0 |
| chase (zombies + villagers, midnight) | vanilla | 26.9 | 54.3 | 22.3 | 29.2 | 2.4 | **12.4** | 9.2 | 24.5 | 18.6 |
|  | +Lithium | 17.9 | 60.2 | 41.7 | 14.2 | 3.4 | **31.2** | – | 10.8 | 11.8 |
| piglins + hoglins (Nether) | vanilla | 35.2 | 60.9 | – | 56.8 | 23.3 | **1.8** | – | 22.0 | 17.6 |
|  | +Lithium | 20.1 | 60.5 | – | 52.9 | 20.9 | **4.2** | – | 13.3 | 14.4 |

**Reading.**
* **Pathfinding is small except when mobs chase.** It is 1–4% for animals, villagers and
  piglins. It is 12% of vanilla's tick and **31% of Lithium's** when zombies keep re-pathing
  to moving targets. Lithium speeds up everything else more than it speeds up A*, so the
  pathfinding share grows once Lithium is on.
* **Brain mobs pay for the brain, not for paths.**
  * In vanilla villagers, 14% of the tick is POI scans: villagers with no bed or workstation
    re-search the area. Lithium cuts that to 2%.
  * With Lithium, most of the remaining cost is behaviour bookkeeping: checking which
    behaviours can start and ticking the running ones.
  * **Sensors are the big chunk that can be split off:** 11% for villagers and 21–23% for
    piglins/hoglins.
* **Movement and collision dominate crowded pens.** The pens are dense (cramming off), so
  movement and collisions are inflated compared with a real base. They aren't AI.

## Options

### 1. Async pathfinding (A* on worker threads)

**Prior art:**
* Petal → Kaiiju → Leaf and DivineMC (Paper forks)
* PathWeaver (Fabric 26.2, MIT, on Modrinth)

**Mechanism** (verified in Leaf's patch and PathWeaver's README):
* `prepare()` and the start node stay on the tick thread. Only the A* loop runs on a pool.
* The pool reads **live chunks** through `PathNavigationRegion`. PathWeaver measured a
  private snapshot and rejected it as too expensive.
* The shared `PathTypeCache` is bypassed: Leaf turns it off off-thread, PathWeaver uses a
  per-thread cache.
* The result is installed **at least one tick later**. Until then the mob keeps its old path
  or stands still.

**Known problems** (claims from Leaf issues and PRs):
* bees not returning to hives
* frog and axolotl evaluators mixed up
* villagers keeping failed POI attempts
* "unreachable" timers reset

Mobs whose behaviour depends on getting a path the same tick need special cases. Leaf patches
`AcquirePoi`, bees, frogs, rabbits, striders, wardens and drowned.

**Gain:** only in chase/maze-style loads. Lab: up to 31% of the tick on Lithium. PathWeaver
claims 43–48% lower mean MSPT for 1024 zombies in a maze, and "no async benefit when MSPT is
under 50".

**Verdict:** worth it for anarchy-style hostile mob loads, as an option. **Use PathWeaver
rather than writing our own.** It boots next to MultithreadMC; the lab measurements are below.

### 2. Parallel sensor phase (the best fit for this mod)

**Prior art:** DivineMC patch "Parallel sensor phase" (verified in source).
* Before entity ticking, the due sensors of all mobs run on a pool while the tick thread
  **waits**, so the world cannot change meanwhile.
* Then the normal brain tick skips the sensors that already ran.
* Only a whitelist of audited sensor classes runs this way. DivineMC also made
  `TargetingConditions` thread-local.

**Why it fits:**
* Sensors only read the world and write their own mob's memories, so there are no
  cross-mob writes.
* Inside a MultithreadMC level worker the same idea works per level, using the idle cores.
  With 3 dimensions busy that is up to 3 pools, still at most `threads` per level.

**Behaviour change:** sensors see the start-of-tick world instead of a world where some
mobs already moved this tick. DivineMC accepts that; it would be checked with the same kind
of fingerprint tests as TNT.

**Gain:** the sensor share: 11% (villagers) to 21% (piglins), divided by the pool size.

### 3. Whole brains or goal selectors on other threads

Brain behaviours and goals write the world (doors, POI claims, attacks, item pickup) and
other mobs' state. Async (the Fabric mod) does it by ticking whole entities in parallel
behind coarse locks, and is alpha with crash and deadlock reports. Leaf removed its "async
target finding" in 1.21.8.

**Verdict: not safely possible** without the region model. That needs the same isolation as
regions, which isn't wanted yet.

### 4. Doing less work (single-thread, no ordering change)

* **Lithium:** cuts each AI scenario by 33–59% in the lab.
* **Throttling** (DAB / activation range / ServerCore): tick brains of far-away mobs less
  often. This changes behaviour (farms; Pufferfish's docs warn about it, and Leaf blacklists
  villagers, axolotls, …), so make it opt-in per mob type.

## Recommendation

1. **Keep Lithium on.** It is the biggest win and has no ordering change.
2. **For hostile-heavy servers, try PathWeaver** in the lab first: the A/B below, then the TNT
   and stress suites with it loaded.
3. **If we build something,** build the parallel sensor phase inside the level workers, with
   an audited whitelist and a fingerprint test before enabling any sensor. Expected gain:
   about 10–20% of the tick on brain-heavy loads.
4. **Leave whole-brain/goal threading** until regions exist.

## PathWeaver in the lab

From `lab/profile_ai.py --scenario chase`: 400 zombies and about 160 villagers in the
Overworld pen at midnight. PathWeaver 0.9.0+26.2, default settings: 2 worker threads,
`maxInFlight` 256. MSPT was taken during the 30 s recording. Pathfinding is its share of
tick-thread CPU. "Villagers left" counts villagers before and after the recording, as a
behaviour check that zombies still find and catch their targets.

| Variant | MSPT | Pathfinding on tick thread | Villagers left (before → after) |
| --- | --- | --- | --- |
| vanilla | 27.0 | 14.3% | 162 → 92 |
| vanilla + PathWeaver | **21.5 (−20%)** | 1.0% | 138 → 29 |
| Lithium | 21.3 | 29.7% | 158 → 52 |
| Lithium + PathWeaver | **15.7 (−26%)** | 1.5% | 156 → 41 |
| MultithreadMC + PathWeaver | 24.6 | 0.9% | 152 → 53 |

**Reading.**
* **The A* search moves off the tick thread almost entirely,** and MSPT drops 20–26%. The
  search still costs CPU on PathWeaver's 2 threads.
* **Zombies still catch villagers.** The paths arrive at least a tick late, but the chase
  works in every variant. The exact counts vary run to run (single runs), so they show it
  works, not that it is equal.
* **PathWeaver runs on MultithreadMC's dimension threads too:** it boots, the selftest
  passes, and pathfinding drops to 0.9% with chasing intact. In this scenario all the load is
  in the Overworld, so MultithreadMC adds nothing on top. Compare the third and fifth rows
  only loosely.
* **Not yet run with PathWeaver:** the TNT suite, the bot scenarios, and the known problem
  mobs (bees, frogs, axolotls, villager POIs). Do that before using it on a live server.
