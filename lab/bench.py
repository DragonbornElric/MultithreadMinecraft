"""MultithreadMC benchmark: same server, same load, one mod variant per run.

    python lab/bench.py --variants vanilla mtmc --mobs 1200 --dims overworld the_nether the_end

For each variant: start the server (run_server.sh), build a walled glass pen at y=200 in each
loaded dimension, force-load it, summon the mobs, let them settle, then measure:

* ``mspt``: ``/tick query`` (average and percentiles of the last 100 ticks), sampled several
  times at the normal 20 TPS. This is the tick's work time, so lower is better.
* ``sprint``: ``/tick sprint N``: ticks as fast as possible and logs the achieved rate.

Results go to stdout and are appended to ``$LAB_DIR/results.jsonl``. The world is deleted
before every run so each variant starts from the same seed and the same pens.
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import random
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from rcon import Rcon  # noqa: E402

LAB_DIR = Path(os.environ.get("LAB_DIR", "/tmp/mtmc-lab"))
SERVER = LAB_DIR / "server"
PEN_Y = 200
HALF = 40  # pen is 80x80 blocks: 5x5 chunks... rounded out to whole chunks by forceload


def rcon() -> Rcon:
    return Rcon(password="mtmclab")


def start(variant: str) -> None:
    subprocess.run([str(HERE / "stop_server.sh"), str(LAB_DIR)], check=False)  # before deleting its world
    shutil.rmtree(SERVER / "world", ignore_errors=True)
    out = subprocess.run([str(HERE / "run_server.sh"), variant, str(LAB_DIR)], capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit(f"server failed to start ({variant}):\n{out.stderr}")
    time.sleep(3)


def gamerule(r: Rcon, *names_and_value: str) -> None:
    *names, value = names_and_value
    for n in names:  # 26.x renamed game rules to snake_case; try both spellings
        if "Unknown" not in (resp := r.cmd(f"gamerule {n} {value}")) and "Incorrect" not in resp:
            return


def build_pen(r: Rcon, dim: str) -> None:
    ex = f"execute in minecraft:{dim} run "
    r.cmd(ex + f"forceload add {-HALF} {-HALF} {HALF - 1} {HALF - 1}")
    # floor, walls, nothing on top (fill is limited to 32768 blocks per call: split the floor)
    for x0 in range(-HALF - 1, HALF + 1, 20):
        r.cmd(ex + f"fill {x0} {PEN_Y} {-HALF - 1} {min(x0 + 19, HALF)} {PEN_Y} {HALF} minecraft:glass")
    for (x1, z1, x2, z2) in [(-HALF - 1, -HALF - 1, HALF, -HALF - 1), (-HALF - 1, HALF, HALF, HALF),
                             (-HALF - 1, -HALF - 1, -HALF - 1, HALF), (HALF, -HALF - 1, HALF, HALF)]:
        r.cmd(ex + f"fill {x1} {PEN_Y + 1} {z1} {x2} {PEN_Y + 3} {z2} minecraft:glass")
    # grass so animals have something to wander to
    r.cmd(ex + f"fill {-HALF} {PEN_Y} {-HALF} {HALF - 1} {PEN_Y} {HALF - 1} minecraft:grass_block replace minecraft:glass")


def summon(r: Rcon, dim: str, count: int, mix: list[str], rng: random.Random) -> None:
    ex = f"execute in minecraft:{dim} run "
    for i in range(count):
        mob = mix[i % len(mix)]
        x = rng.uniform(-HALF + 1, HALF - 2)
        z = rng.uniform(-HALF + 1, HALF - 2)
        r.cmd(ex + f"summon minecraft:{mob} {x:.2f} {PEN_Y + 1} {z:.2f} {{PersistenceRequired:1b}}")


def tick_query(r: Rcon) -> dict:
    txt = r.cmd("tick query")
    m = re.search(r"Average time per tick: ([\d.]+)ms", txt)
    p = dict(re.findall(r"(P\d+): ([\d.]+)ms", txt))
    return {"avg": float(m.group(1)) if m else None, **{k: float(v) for k, v in p.items()}}


def sprint(r: Rcon, ticks: int, timeout: float) -> dict:
    log = SERVER / "server.log"
    start_size = log.stat().st_size
    t0 = time.time()
    r.cmd(f"tick sprint {ticks}")
    pat = re.compile(r"Sprint completed with ([\d.]+) ticks per second, or ([\d.]+) ms per tick")
    while time.time() - t0 < timeout:
        with log.open("rb") as f:
            f.seek(start_size)
            m = pat.search(f.read().decode(errors="replace"))
        if m:
            return {"tps": float(m.group(1)), "mspt": float(m.group(2)), "wall_s": round(time.time() - t0, 1)}
        time.sleep(1)
    r.cmd("tick sprint stop")
    return {"tps": None, "mspt": None, "wall_s": round(time.time() - t0, 1), "timeout": True}


def mtmc_stats() -> dict | None:
    f = SERVER / "mtmc-stats.json"
    try:
        return json.loads(f.read_text())
    except (OSError, ValueError):
        return None


def host_info() -> dict:
    """CPU of the machine the run was on, so runs from different PCs can be told apart."""
    cpu = platform.processor() or platform.machine()
    try:
        for line in open("/proc/cpuinfo"):
            if line.startswith("model name"):
                cpu = line.split(":", 1)[1].strip()
                break
    except OSError:
        pass
    return {"cpu": cpu, "logical_cpus": os.cpu_count(), "os": platform.platform(), "label": os.environ.get("LAB_HOST", "")}


def run(variant: str, args) -> dict:
    start(variant)
    r = rcon()
    gamerule(r, "max_entity_cramming", "maxEntityCramming", "0")
    gamerule(r, "advance_time", "doDaylightCycle", "false")
    gamerule(r, "spawn_mobs", "doMobSpawning", "false")
    r.cmd("time set noon")
    rng = random.Random(args.seed)
    for dim in args.dims:
        build_pen(r, dim)
    for dim in args.dims:
        n = args.mobs if dim == args.dims[0] or not args.skew else int(args.mobs * args.skew)
        summon(r, dim, n, args.mix, rng)
    counts = {d: r.cmd(f"execute in minecraft:{d} if entity @e[x=0,y={PEN_Y},z=0,distance=..{HALF * 2}]").strip() for d in args.dims}
    time.sleep(args.settle)
    samples = []
    for _ in range(args.samples):
        time.sleep(5.5)  # 100 ticks at 20 TPS between samples
        samples.append(tick_query(r))
    sp = sprint(r, args.sprint, timeout=args.sprint_timeout) if args.sprint else None
    stats = mtmc_stats()
    crashed = "Exception" in (SERVER / "server.log").read_text(errors="replace")
    r.close()
    avg = [s["avg"] for s in samples if s.get("avg") is not None]
    p95 = [s["P95"] for s in samples if s.get("P95") is not None]
    res = {
        "variant": variant, "mobs_per_dim": args.mobs, "skew": args.skew, "dims": args.dims, "mix": args.mix,
        "entity_counts": counts,
        "mspt_avg": round(sum(avg) / len(avg), 2) if avg else None,
        "mspt_p95": round(sum(p95) / len(p95), 2) if p95 else None,
        "samples": samples, "sprint": sp, "mtmc": stats, "exception_in_log": crashed,
        "time": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "host": host_info(),
    }
    subprocess.run([str(HERE / "stop_server.sh"), str(LAB_DIR)], check=False)
    return res


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--variants", nargs="+", default=["vanilla", "mtmc"])
    ap.add_argument("--dims", nargs="+", default=["overworld", "the_nether", "the_end"])
    ap.add_argument("--mobs", type=int, default=1000, help="mobs per dimension")
    ap.add_argument("--skew", type=float, default=0.0,
                    help="other dimensions get mobs*skew (0 = same as the first dimension)")
    ap.add_argument("--mix", nargs="+", default=["cow", "sheep", "chicken", "pig"])
    ap.add_argument("--settle", type=float, default=15)
    ap.add_argument("--samples", type=int, default=4)
    ap.add_argument("--sprint", type=int, default=1200, help="ticks for /tick sprint (0 = skip)")
    ap.add_argument("--sprint-timeout", type=float, default=600)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--repeat", type=int, default=1)
    args = ap.parse_args()
    results = LAB_DIR / "results.jsonl"
    for _ in range(args.repeat):
        for v in args.variants:
            res = run(v, args)
            line = json.dumps(res)
            print(json.dumps({k: res[k] for k in ("variant", "mobs_per_dim", "skew", "mspt_avg", "mspt_p95", "sprint", "exception_in_log")}))
            with results.open("a") as f:
                f.write(line + "\n")


if __name__ == "__main__":
    main()
