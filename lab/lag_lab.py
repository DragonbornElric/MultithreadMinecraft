"""Lag accounting lab: does /mtmc lag point at the lag machines, and what does it cost?

    python lab/lag_lab.py detect                 # reference machines + legit builds, check the ranking
    python lab/lag_lab.py overhead --mobs 1000   # lag accounting on/off A/B on bench.py's mob pens

detect: in the Overworld, each build gets its own chunk along z = 200..., at y = 200 on a glass
floor, force-loaded:

  lag machines                                   legit builds
  * observer_lamps: 256 observer pairs facing     * cow_farm: 40 cows
    each other (2-tick clocks), each powering    * repeater_clock: a 1 Hz repeater loop
    a redstone lamp (redstone + light updates)     with a lamp
  * observer_pistons: 128 observer clocks        * hopper_chain: 30 hoppers in a line
    each driving a sticky piston pushing a         (idle)
    stone (block events, moving blocks)
  * minecart_stack: 300 minecarts in one block
    (entity collisions)

The builds go in one at a time. For each, the test measures how much MSPT went up and how
much /mtmc lag charged to that build's chunk. Pass: every build that added at least 1 ms/tick
was charged within +-50% of what it added (below 1 ms the MSPT noise on a shared VM is too
large to compare). The ranking by charge is printed too.

overhead: bench.py's pens (all three dimensions), then alternates /mtmc lag on and off
every --period seconds, sampling /tick query; reports MSPT with and without accounting.

Results: stdout and $LAB_DIR/lag_lab.jsonl.
"""
from __future__ import annotations

import argparse
import json
import random
import re
import statistics
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import bench  # noqa: E402
from rcon import Rcon  # noqa: E402

LAB_DIR = bench.LAB_DIR
Y = 200
LAYERS = 8  # redstone machines: layers of clocks (8 = 256 observer-lamp clocks, 128 piston clocks)
# cheapest first, the minecart stack last: after a 30+ ms build MSPT swings by several ms,
# which would swamp the small builds measured after it
BUILDS = ["repeater_clock", "hopper_chain", "cow_farm", "observer_lamps", "observer_pistons", "minecart_stack"]
MACHINES = {"observer_lamps", "observer_pistons", "minecart_stack"}


def chunk_origin(i: int) -> tuple[int, int]:
    """Build i lives in chunk (0, 12 + 2*i): one empty chunk between builds."""
    return 0, (12 + 2 * i) * 16


def build(r: Rcon, name: str, x0: int, z0: int) -> None:
    c = lambda cmd: r.cmd("execute in minecraft:overworld run " + cmd)
    c(f"forceload add {x0} {z0} {x0 + 15} {z0 + 15}")
    c(f"fill {x0} {Y - 1} {z0} {x0 + 15} {Y + 2 * LAYERS + 2} {z0 + 15} minecraft:air")
    c(f"fill {x0} {Y - 1} {z0} {x0 + 15} {Y - 1} {z0 + 15} minecraft:glass")
    if name == "observer_lamps":
        # rows of: lamp, observer(east), observer(west), lamp ; the pair clocks itself
        for row in range(0, 16, 2):
            for col in range(0, 16, 4):
                for layer in range(0, 2 * LAYERS, 2):
                    x, y, z = x0 + col, Y + layer, z0 + row
                    c(f"setblock {x} {y} {z} minecraft:redstone_lamp")
                    c(f"setblock {x + 3} {y} {z} minecraft:redstone_lamp")
                    c(f"setblock {x + 2} {y} {z} minecraft:observer[facing=west]")
                    c(f"setblock {x + 1} {y} {z} minecraft:observer[facing=east]")
    elif name == "observer_pistons":
        # unit (6 wide): stone, sticky piston (facing west, pushes the stone), observer
        # (east), observer (west), lamp. The observers clock each other; the first powers
        # the piston from its back, the second the lamp.
        for row in range(0, 16, 2):
            for col in (0, 6):
                for layer in range(0, 2 * LAYERS, 2):
                    x, y, z = x0 + col, Y + layer, z0 + row
                    c(f"setblock {x} {y} {z} minecraft:stone")
                    c(f"setblock {x + 1} {y} {z} minecraft:sticky_piston[facing=west]")
                    c(f"setblock {x + 4} {y} {z} minecraft:redstone_lamp")
                    c(f"setblock {x + 3} {y} {z} minecraft:observer[facing=west]")
                    c(f"setblock {x + 2} {y} {z} minecraft:observer[facing=east]")
    elif name == "minecart_stack":
        for _ in range(300):
            c(f"summon minecraft:minecart {x0 + 8.5} {Y} {z0 + 8.5}")
    elif name == "cow_farm":
        c(f"fill {x0} {Y} {z0} {x0 + 15} {Y} {z0 + 15} minecraft:oak_fence hollow")
        for i in range(40):
            c(f"summon minecraft:cow {x0 + 2 + i % 12}.5 {Y} {z0 + 2 + i // 12 * 3}.5 {{PersistenceRequired:1b}}")
    elif name == "repeater_clock":
        # a ring: dust - repeaters (4 ticks each) - lamp; started by a redstone block pulse
        x, z = x0 + 4, z0 + 4
        c(f"setblock {x} {Y} {z} minecraft:repeater[facing=west,delay=4]")
        c(f"setblock {x + 1} {Y} {z} minecraft:repeater[facing=west,delay=4]")
        c(f"setblock {x + 2} {Y} {z} minecraft:redstone_wire")
        c(f"setblock {x + 2} {Y} {z + 1} minecraft:redstone_wire")
        c(f"setblock {x + 1} {Y} {z + 1} minecraft:repeater[facing=east,delay=4]")
        c(f"setblock {x} {Y} {z + 1} minecraft:repeater[facing=east,delay=4]")
        c(f"setblock {x - 1} {Y} {z + 1} minecraft:redstone_wire")
        c(f"setblock {x - 1} {Y} {z} minecraft:redstone_wire")
        c(f"setblock {x - 1} {Y} {z + 2} minecraft:redstone_lamp")
        c(f"setblock {x - 1} {Y - 1} {z} minecraft:redstone_block")  # pulse it once
        c(f"setblock {x - 1} {Y - 1} {z} minecraft:glass")
    elif name == "hopper_chain":
        for i in range(30):
            c(f"setblock {x0 + (i % 15)} {Y + i // 15} {z0 + 8} minecraft:hopper[facing=east]")


def lag_top(r: Rcon) -> list[dict]:
    out = []
    for line in r.cmd("mtmc lag top 20").split("#")[1:]:
        m = re.match(r"(\d+) (\S+) \[(-?\d+), (-?\d+)\] \(x (-?\d+), z (-?\d+)\): ([\d.]+) ms/t \(peak ([\d.]+)\) - (.*)", line.strip())
        if m:
            out.append({"rank": int(m.group(1)), "dim": m.group(2), "cx": int(m.group(3)), "cz": int(m.group(4)),
                        "ms": float(m.group(7)), "peak": float(m.group(8)), "detail": m.group(9)[:160]})
    return out


def chunk_ms(r: Rcon, x0: int, z0: int) -> tuple[float, str]:
    for t in lag_top(r):
        if t["dim"] == "overworld" and (t["cx"], t["cz"]) == (x0 >> 4, z0 >> 4):
            return t["ms"], t["detail"]
    return 0.0, ""


def mspt_avg(r: Rcon, n: int = 3) -> float:
    vals = []
    for _ in range(n):
        time.sleep(5.5)
        q = bench.tick_query(r)
        if q.get("avg") is not None:
            vals.append(q["avg"])
    return statistics.mean(vals) if vals else 0.0


def detect(args) -> dict:
    """Add the builds one at a time. For each: how much MSPT went up, and how much /mtmc lag
    charged to its chunk. The accounting is right if the two agree (within noise), and the
    ranking is right if it orders builds by what they really cost."""
    if not args.running:
        bench.start(args.variant)
    r = Rcon(password="mtmclab")
    bench.gamerule(r, "spawn_mobs", "doMobSpawning", "false")
    bench.gamerule(r, "max_entity_cramming", "maxEntityCramming", "0")
    time.sleep(5)
    base = mspt_avg(r)
    builds = {}
    for i, name in enumerate(BUILDS):
        x0, z0 = chunk_origin(i)
        build(r, name, x0, z0)
        time.sleep(args.settle)
        now = mspt_avg(r)
        ms, detail = chunk_ms(r, x0, z0)
        builds[name] = {"mspt_added": round(now - base, 2), "charged_ms": ms, "detail": detail,
                        "machine": name in MACHINES}
        print(f"  {name:18s} MSPT +{now - base:6.2f}  charged {ms:6.2f} ms/t  {detail[:90]}", flush=True)
        base = now
    top = lag_top(r)
    order = [n for n, _ in sorted(builds.items(), key=lambda kv: -kv[1]["charged_ms"])]
    big = [b for b in builds.values() if b["mspt_added"] >= 1.0]
    within = [b for b in big if b["charged_ms"] >= 0.5 * b["mspt_added"] and b["charged_ms"] <= 1.5 * b["mspt_added"]]
    res = {"mode": "detect", "variant": (bench.SERVER / "variant.txt").read_text().strip(), "builds": builds,
           "ranking_by_charge": order,
           "accuracy": f"{len(within)}/{len(big)} builds that added >= 1 ms/t were charged within +-50%",
           "ok": len(within) == len(big), "top": top[:8], "time": time.strftime("%Y-%m-%dT%H:%M:%S")}
    r.close()
    return res


def overhead(args) -> dict:
    if not args.running:
        bench.start(args.variant)
    r = Rcon(password="mtmclab")
    bench.gamerule(r, "max_entity_cramming", "maxEntityCramming", "0")
    bench.gamerule(r, "spawn_mobs", "doMobSpawning", "false")
    bench.gamerule(r, "advance_time", "doDaylightCycle", "false")
    rng = random.Random(1)
    for d in args.dims:
        bench.build_pen(r, d)
        bench.summon(r, d, args.mobs, ["cow", "sheep", "chicken", "pig"], rng)
    time.sleep(args.settle)
    samples = {"on": [], "off": []}
    state = "on"
    t0 = time.time()
    while time.time() - t0 < args.minutes * 60:
        r.cmd(f"mtmc lag {state}")
        time.sleep(6)  # let /tick query's 100-tick window fill with this state
        for _ in range(max(1, int(args.period // 5.5) - 1)):
            time.sleep(5.5)
            q = bench.tick_query(r)
            if q.get("avg") is not None:
                samples[state].append(q["avg"])
        state = "off" if state == "on" else "on"
    r.cmd("mtmc lag on")
    r.close()
    on, off = samples["on"], samples["off"]
    res = {"mode": "overhead", "variant": (bench.SERVER / "variant.txt").read_text().strip(), "mobs_per_dim": args.mobs,
           "mspt_on": round(statistics.mean(on), 2) if on else None, "mspt_off": round(statistics.mean(off), 2) if off else None,
           "n_on": len(on), "n_off": len(off), "samples": samples, "time": time.strftime("%Y-%m-%dT%H:%M:%S")}
    if on and off:
        res["overhead_pct"] = round(100 * (res["mspt_on"] - res["mspt_off"]) / res["mspt_off"], 1)
    return res


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["detect", "overhead"])
    ap.add_argument("--variant", default="mtmc")
    ap.add_argument("--running", action="store_true")
    ap.add_argument("--settle", type=float, default=20)
    ap.add_argument("--mobs", type=int, default=1000)
    ap.add_argument("--dims", nargs="+", default=["overworld", "the_nether", "the_end"])
    ap.add_argument("--minutes", type=float, default=6)
    ap.add_argument("--period", type=float, default=40)
    args = ap.parse_args()
    res = detect(args) if args.mode == "detect" else overhead(args)
    print(json.dumps(res, indent=1))
    with (LAB_DIR / "lag_lab.jsonl").open("a") as f:
        f.write(json.dumps(res) + "\n")


if __name__ == "__main__":
    main()
