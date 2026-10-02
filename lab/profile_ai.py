"""Where does a mob-heavy tick go? Java Flight Recorder + attribution by call stack.

    python lab/profile_ai.py --scenario villagers --mobs 300
    python lab/profile_ai.py --scenario all

Starts the server (vanilla unless --variant), builds the Overworld pen from bench.py, summons
a scenario, lets it settle, records --seconds of JFR CPU samples ("profile" settings, about
every 10-20 ms per thread), then attributes every sample taken on a tick thread (Server
thread or an MTMC level thread) to categories by its call stack. Categories are inclusive and
nest (pathfinding is part of goals/brain, which is part of the mob AI step), so read them as
"share of tick time spent inside X".

Scenarios (all in the Overworld pen, persistent mobs, no natural spawning):
* animals:   cows, sheep, pigs, chickens (goal-selector AI, wandering)
* villagers: villagers (brain AI, sensors, POI lookups; no beds or workstations to claim)
* chase:     zombies + villagers, midnight (zombies keep pathing to villagers, villagers flee)
* piglins:   piglins and hoglins in the Nether pen (brain AI, hostile sensors)

Each result also has a mob census before and after the recording (a behaviour check: in
`chase`, how many villagers the zombies caught). Results: stdout and $LAB_DIR/profile_ai.jsonl. The .jfr files stay in $LAB_DIR/jfr/.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import re
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import bench  # noqa: E402
from rcon import Rcon  # noqa: E402

LAB_DIR = bench.LAB_DIR

# (category, regex on "Class.method" frames). Inclusive: a sample counts for every category
# with a matching frame anywhere in its stack.
CATEGORIES = [
    ("level tick (all)", r"ServerLevel\.tick$"),
    ("entity ticks", r"ServerLevel\.tickNonPassenger$|ServerLevel\.tickPassenger$"),
    ("mob AI step (serverAiStep)", r"Mob\.serverAiStep$"),
    ("goal selectors", r"GoalSelector\.tick$|GoalSelector\.tickRunningGoals$"),
    ("brains", r"Brain\.tick$"),
    ("sensors", r"Sensor\.tick$|Sensing\.tick$|Sensor\.doTick$"),
    ("pathfinding (find path)", r"PathFinder\.findPath$"),
    ("path following (navigation tick)", r"PathNavigation\.tick$"),
    ("POI lookups", r"PoiManager\."),
    ("movement (travel/move)", r"LivingEntity\.travel$|Entity\.move$"),
    ("collisions/pushing", r"LivingEntity\.pushEntities$|Level\.getEntities$|EntitySectionStorage\.|Entity\.collide$"),
    ("entity tracker (send updates)", r"ChunkMap\.tick$|ChunkMap\$TrackedEntity\.|ServerEntity\.sendChanges$"),
    ("block entities", r"Level\.tickBlockEntities$"),
    ("block/fluid ticks", r"LevelTicks\.tick$"),
    ("chunk source (spawning, random ticks)", r"ServerChunkCache\.tick$"),
]


def summon_scenario(r: Rcon, scenario: str, mobs: int, rng: random.Random) -> None:
    mixes = {
        "animals": ("overworld", ["cow", "sheep", "pig", "chicken"]),
        "villagers": ("overworld", ["villager"]),
        "chase": ("overworld", ["zombie", "zombie", "villager"]),
        "piglins": ("the_nether", ["piglin", "piglin", "hoglin"]),
    }
    dim, mix = mixes[scenario]
    bench.build_pen(r, dim)
    if scenario == "chase":
        r.cmd("time set midnight")
    bench.summon(r, dim, mobs, mix, rng)


def census(r: Rcon) -> dict:
    """Mob counts in all dimensions: a behaviour check (e.g. how many villagers the zombies got)."""
    out = {}
    for t in ("villager", "zombie", "zombie_villager", "piglin", "hoglin", "cow"):
        n = 0
        for d in ("overworld", "the_nether"):
            m = re.search(r"Count: (\d+)", r.cmd(f"execute in minecraft:{d} if entity @e[type=minecraft:{t},x=0,y=0,z=0,distance=..100000]"))
            n += int(m.group(1)) if m else 0
        if n:
            out[t] = n
    return out


def record(seconds: int, pid: int, name: str) -> Path:
    out = LAB_DIR / "jfr" / f"{name}.jfr"
    out.parent.mkdir(parents=True, exist_ok=True)
    jcmd = Path(os.environ.get("JAVA_HOME", "")) / "bin" / "jcmd"
    jcmd = str(jcmd) if jcmd.exists() else "jcmd"
    subprocess.run([jcmd, str(pid), "JFR.start", f"name={name}", "settings=profile", f"duration={seconds}s",
                    f"filename={out}"], check=True, capture_output=True)
    time.sleep(seconds + 4)
    return out


def attribute(jfr: Path) -> dict:
    jfr_bin = Path(os.environ.get("JAVA_HOME", "")) / "bin" / "jfr"
    jfr_bin = str(jfr_bin) if jfr_bin.exists() else "jfr"
    txt = subprocess.run([jfr_bin, "print", "--events", "jdk.ExecutionSample", "--stack-depth", "200", str(jfr)],
                         capture_output=True, text=True, check=True).stdout
    total = 0
    counts: Counter = Counter()
    top: Counter = Counter()
    pats = [(n, re.compile(p)) for n, p in CATEGORIES]
    for ev in txt.split("jdk.ExecutionSample")[1:]:
        th = re.search(r'sampledThread = "([^"]+)"', ev)
        if not th or not (th.group(1) == "Server thread" or th.group(1).startswith("MTMC Level Thread")):
            continue
        frames = re.findall(r"^\s+([\w$.]+)\(", ev, re.M)
        if not frames:
            continue
        total += 1
        short = [".".join(f.split(".")[-2:]) for f in frames]
        for name, pat in pats:
            if any(pat.search(f) for f in short):
                counts[name] += 1
        top[short[0]] += 1
    return {
        "tick_thread_samples": total,
        "share_pct": {n: round(100 * counts[n] / total, 1) if total else 0 for n, _ in CATEGORIES},
        "top_self_frames": [(f, round(100 * c / total, 1)) for f, c in top.most_common(15)] if total else [],
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", default="all", choices=["all", "animals", "villagers", "chase", "piglins"])
    ap.add_argument("--mobs", type=int, default=600)
    ap.add_argument("--variant", default="vanilla")
    ap.add_argument("--seconds", type=int, default=30)
    ap.add_argument("--settle", type=int, default=20)
    args = ap.parse_args()
    scenarios = ["animals", "villagers", "chase", "piglins"] if args.scenario == "all" else [args.scenario]
    for sc in scenarios:
        bench.start(args.variant)
        r = Rcon(password="mtmclab")
        bench.gamerule(r, "max_entity_cramming", "maxEntityCramming", "0")
        bench.gamerule(r, "spawn_mobs", "doMobSpawning", "false")
        bench.gamerule(r, "advance_time", "doDaylightCycle", "false")
        summon_scenario(r, sc, args.mobs, random.Random(1))
        time.sleep(args.settle)
        mspt = [bench.tick_query(r) for _ in range(1)]
        census_before = census(r)
        pid = int((bench.SERVER / "server.pid").read_text())
        name = f"{sc}-{args.variant}-{time.strftime('%H%M%S')}"
        jfr = record(args.seconds, pid, name)
        mspt.append(bench.tick_query(r))
        census_after = census(r)
        r.close()
        res = {"scenario": sc, "variant": args.variant, "mobs": args.mobs, "mspt": mspt, "jfr": str(jfr),
               "census": {"before": census_before, "after": census_after},
               **attribute(jfr), "host": bench.host_info(), "time": time.strftime("%Y-%m-%dT%H:%M:%S")}
        print(json.dumps({k: res[k] for k in ("scenario", "variant", "mobs", "mspt", "census", "tick_thread_samples", "share_pct")}), flush=True)
        print("  top self frames:", res["top_self_frames"][:10], flush=True)
        with (LAB_DIR / "profile_ai.jsonl").open("a") as f:
            f.write(json.dumps(res) + "\n")
        subprocess.run([str(HERE / "stop_server.sh"), str(LAB_DIR)], check=False)


if __name__ == "__main__":
    main()
