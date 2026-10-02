"""Parallel sensor phase lab: does it save tick time, and do mobs still behave the same?

    python lab/sensor_lab.py ab --scenario piglins --mobs 600     # /mtmc sensors on/off A/B, same load
    python lab/sensor_lab.py behaviour                           # gold pickup + villager panic, off vs on

ab: bench.py's pen (piglins/hoglins in the Nether, or villagers in the Overworld), then
alternates /mtmc sensors on and off every --period seconds, sampling /tick query.

behaviour: each check runs twice on identical fresh setups, phase off then on:
  * gold: 10 adult piglins (immune to zombification) in a glass pen in the Nether with 60 gold
    ingots on the floor. Their NearestItemSensor finds the gold, they pick it up and admire it.
    Measured: gold ingots left on the floor after 3, 10 and 30 s.
  * panic: 20 villagers around a no-AI zombie in a 31x31 pen with a grass floor (villagers
    don't walk on glass), at noon (they rest at night; the zombie wears a helmet so it doesn't
    burn). Their VillagerHostilesSensor
    sees it, they panic and run. Measured: mean villager distance from the zombie at the start
    and after 12 s.
Pass: the phase-on numbers are in the same range as phase-off (the AI uses randomness, so
they're compared loosely), and nothing logged an exception.

Results: stdout and $LAB_DIR/sensor_lab.jsonl.
"""
from __future__ import annotations

import argparse
import json
import math
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


def ab(args) -> dict:
    if not args.running:
        bench.start(args.variant)
    r = Rcon(password="mtmclab")
    bench.gamerule(r, "max_entity_cramming", "maxEntityCramming", "0")
    bench.gamerule(r, "spawn_mobs", "doMobSpawning", "false")
    rng = random.Random(1)
    if args.scenario == "piglins":
        bench.build_pen(r, "the_nether")
        for i in range(args.mobs):
            kind = "hoglin" if i % 3 == 2 else "piglin"
            x, z = rng.uniform(-bench.HALF + 1, bench.HALF - 2), rng.uniform(-bench.HALF + 1, bench.HALF - 2)
            r.cmd(f"execute in minecraft:the_nether run summon minecraft:{kind} {x:.2f} {Y + 1} {z:.2f} "
                  "{PersistenceRequired:1b,IsImmuneToZombification:1b}")
    else:
        bench.build_pen(r, "overworld")
        bench.summon(r, "overworld", args.mobs, ["villager"], rng)
    time.sleep(args.settle)
    samples = {"on": [], "off": []}
    state = "on"
    t0 = time.time()
    while time.time() - t0 < args.minutes * 60:
        r.cmd(f"mtmc sensors {state}")
        time.sleep(6)
        for _ in range(max(1, int(args.period // 5.5) - 1)):
            time.sleep(5.5)
            q = bench.tick_query(r)
            if q.get("avg") is not None:
                samples[state].append(q["avg"])
        state = "off" if state == "on" else "on"
    stats = r.cmd("mtmc sensors")
    r.cmd("mtmc sensors off")
    r.close()
    on, off = samples["on"], samples["off"]
    res = {"mode": "ab", "scenario": args.scenario, "mobs": args.mobs,
           "mspt_on": round(statistics.mean(on), 2), "mspt_off": round(statistics.mean(off), 2),
           "n_on": len(on), "n_off": len(off), "sensor_stats": stats, "samples": samples,
           "host": bench.host_info(), "time": time.strftime("%Y-%m-%dT%H:%M:%S")}
    res["change_pct"] = round(100 * (res["mspt_on"] - res["mspt_off"]) / res["mspt_off"], 1)
    return res


def count(r: Rcon, dim: str, sel: str) -> int:
    m = re.search(r"Count: (\d+)", r.cmd(f"execute in minecraft:{dim} if entity {sel}"))
    return int(m.group(1)) if m else 0


def positions(r: Rcon, dim: str, sel: str, n: int) -> list[tuple[float, float]]:
    out = []
    for i in range(n):
        txt = r.cmd(f"execute in minecraft:{dim} run data get entity {sel[:-1]},tag=v{i},limit=1] Pos")
        nums = re.findall(r"(-?[\d.]+)d", txt)
        if len(nums) >= 3:
            out.append((float(nums[0]), float(nums[2])))
    return out


def gold(r: Rcon, x0: int, z0: int) -> dict:
    ex = "execute in minecraft:the_nether run "
    r.cmd(ex + f"forceload add {x0} {z0} {x0 + 31} {z0 + 31}")
    r.cmd(ex + f"fill {x0} {Y - 1} {z0} {x0 + 21} {Y + 4} {z0 + 21} minecraft:air")
    r.cmd(ex + f"fill {x0} {Y - 1} {z0} {x0 + 21} {Y + 4} {z0 + 21} minecraft:glass hollow")
    r.cmd(ex + f"fill {x0 + 1} {Y + 4} {z0 + 1} {x0 + 20} {Y + 4} {z0 + 20} minecraft:air")
    rng = random.Random(7)
    for i in range(10):
        r.cmd(ex + f"summon minecraft:piglin {x0 + 2 + rng.random() * 18:.2f} {Y} {z0 + 2 + rng.random() * 18:.2f} "
                   "{PersistenceRequired:1b,IsImmuneToZombification:1b,IsBaby:0b,Tags:[\"lab_piglin\"]}")
    time.sleep(3)
    for i in range(60):
        r.cmd(ex + f"summon minecraft:item {x0 + 2 + rng.random() * 18:.2f} {Y} {z0 + 2 + rng.random() * 18:.2f} "
                   "{Item:{id:\"minecraft:gold_ingot\",count:1},Tags:[\"lab_gold\"]}")
    sel = f"@e[type=minecraft:item,tag=lab_gold,x={x0},y={Y - 2},z={z0},dx=22,dy=8,dz=22]"
    time.sleep(3)
    left3 = count(r, "the_nether", sel)
    time.sleep(7)
    left10 = count(r, "the_nether", sel)
    time.sleep(20)
    left30 = count(r, "the_nether", sel)
    r.cmd(ex + f"kill @e[x={x0},y={Y - 2},z={z0},dx=22,dy=8,dz=22,type=!minecraft:player]")
    return {"gold_dropped": 60, "piglins": 10, "left_after_3s": left3, "left_after_10s": left10, "left_after_30s": left30}


def panic(r: Rcon, x0: int, z0: int) -> dict:
    ex = "execute in minecraft:overworld run "
    r.cmd(ex + f"forceload add {x0} {z0} {x0 + 31} {z0 + 31}")
    r.cmd(ex + f"fill {x0} {Y - 1} {z0} {x0 + 30} {Y + 3} {z0 + 30} minecraft:air")
    r.cmd(ex + f"fill {x0} {Y - 1} {z0} {x0 + 30} {Y + 3} {z0 + 30} minecraft:glass hollow")
    r.cmd(ex + f"fill {x0 + 1} {Y + 3} {z0 + 1} {x0 + 29} {Y + 3} {z0 + 29} minecraft:air")
    # villagers don't walk on glass (no walk target on it, mod on or off); grass floor inside
    r.cmd(ex + f"fill {x0 + 1} {Y - 1} {z0 + 1} {x0 + 29} {Y - 1} {z0 + 29} minecraft:grass_block")
    cx, cz = x0 + 15.5, z0 + 15.5
    # helmet: noon, so villagers are awake, and the zombie doesn't burn
    r.cmd(ex + f"summon minecraft:zombie {cx} {Y} {cz} {{NoAI:1b,PersistenceRequired:1b,Tags:[\"lab_zombie\"],"
               "equipment:{head:{id:\"minecraft:leather_helmet\",count:1}}}")
    rng = random.Random(3)
    for i in range(20):
        a = rng.random() * 2 * math.pi
        d = 2 + rng.random() * 3
        r.cmd(ex + f"summon minecraft:villager {cx + d * math.cos(a):.2f} {Y} {cz + d * math.sin(a):.2f} "
                   f"{{PersistenceRequired:1b,Tags:[\"lab_villager\",\"v{i}\"]}}")
    sel = f"@e[type=minecraft:villager,tag=lab_villager,x={x0},y={Y - 2},z={z0},dx=31,dy=6,dz=31]"
    time.sleep(2)
    start = positions(r, "overworld", sel, 20)
    time.sleep(12)
    end = positions(r, "overworld", sel, 20)
    dist = lambda ps: statistics.mean(math.hypot(px - cx, pz - cz) for px, pz in ps) if ps else 0
    r.cmd(ex + f"kill @e[x={x0},y={Y - 2},z={z0},dx=31,dy=6,dz=31,type=!minecraft:player]")
    return {"villagers": len(end), "mean_dist_start": round(dist(start), 2), "mean_dist_after_12s": round(dist(end), 2)}


def memories(r: Rcon, dim: str, sel: str, n: int) -> list[str]:
    """Each tagged villager's brain memories and sleeping state as one text blob (empty if gone)."""
    out = []
    for i in range(n):
        one = f"{sel[:-1]},tag=v{i},limit=1]"
        mem = r.cmd(f"execute in minecraft:{dim} run data get entity {one} Brain.memories")
        sleep = r.cmd(f"execute in minecraft:{dim} run data get entity {one} sleeping_pos")
        out.append(mem + (" SLEEPING" if re.search(r"has the following entity data", sleep) else ""))
    return out


def village(r: Rcon, x0: int, z0: int) -> dict:
    """A small village on grass: a bell, 10 beds, 5 workstations, 10 adult villagers.
    Day (time 2000): how many claimed a bed (home) and a workstation (job_site) after 40 s.
    Meeting time (9500): how many know the bell (meeting_point) and their mean distance to it.
    Night (13000): how many are asleep after 25 s."""
    ex = "execute in minecraft:overworld run "
    n, w = 10, 40
    r.cmd(ex + f"forceload add {x0} {z0} {x0 + w} {z0 + w}")
    r.cmd(ex + f"fill {x0} {Y - 1} {z0} {x0 + w} {Y + 3} {z0 + w} minecraft:air")
    r.cmd(ex + f"fill {x0} {Y - 1} {z0} {x0 + w} {Y + 3} {z0 + w} minecraft:glass hollow")
    r.cmd(ex + f"fill {x0 + 1} {Y + 3} {z0 + 1} {x0 + w - 1} {Y + 3} {z0 + w - 1} minecraft:air")
    r.cmd(ex + f"fill {x0 + 1} {Y - 1} {z0 + 1} {x0 + w - 1} {Y - 1} {z0 + w - 1} minecraft:grass_block")
    bx, bz = x0 + w // 2, z0 + w // 2
    r.cmd(ex + f"setblock {bx} {Y} {bz} minecraft:bell[attachment=floor]")
    for i in range(n):  # a row of beds along the north side, foot then head (facing south)
        x = x0 + 5 + 3 * i
        r.cmd(ex + f"setblock {x} {Y} {z0 + 4} minecraft:white_bed[facing=south,part=foot]")
        r.cmd(ex + f"setblock {x} {Y} {z0 + 5} minecraft:white_bed[facing=south,part=head]")
    for i, ws in enumerate(["composter", "lectern", "smithing_table", "barrel", "cartography_table"]):
        r.cmd(ex + f"setblock {x0 + 8 + 6 * i} {Y} {z0 + w - 5} minecraft:{ws}")
    r.cmd("time set 2000")
    rng = random.Random(5)
    for i in range(n):
        r.cmd(ex + f"summon minecraft:villager {x0 + 4 + rng.random() * (w - 8):.2f} {Y} {z0 + 10 + rng.random() * (w - 20):.2f} "
                   f"{{PersistenceRequired:1b,Age:0,Tags:[\"lab_villager\",\"v{i}\"]}}")
    sel = f"@e[type=minecraft:villager,tag=lab_villager,x={x0},y={Y - 2},z={z0},dx={w},dy=6,dz={w}]"
    time.sleep(40)
    day = memories(r, "overworld", sel, n)
    r.cmd("time set 9500")
    time.sleep(25)
    meet = memories(r, "overworld", sel, n)
    pos = positions(r, "overworld", sel, n)
    r.cmd("time set 13000")
    time.sleep(25)
    night = memories(r, "overworld", sel, n)
    r.cmd("time set noon")
    r.cmd(ex + f"kill @e[x={x0},y={Y - 2},z={z0},dx={w},dy=6,dz={w},type=!minecraft:player]")
    has = lambda blobs, key: sum(key in b for b in blobs)
    return {"villagers": len(pos), "home_after_40s": has(day, "minecraft:home"),
            "job_site_after_40s": has(day, "minecraft:job_site"),
            "meeting_point_at_9500": has(meet, "minecraft:meeting_point"),
            "mean_dist_to_bell_at_9500": round(statistics.mean(math.hypot(px - bx - .5, pz - bz - .5) for px, pz in pos), 2) if pos else None,
            "asleep_at_13000": has(night, "SLEEPING")}


def behaviour(args) -> dict:
    if not args.running:
        bench.start(args.variant)
    r = Rcon(password="mtmclab")
    bench.gamerule(r, "spawn_mobs", "doMobSpawning", "false")
    bench.gamerule(r, "advance_time", "doDaylightCycle", "false")
    r.cmd("time set noon")  # villagers rest at night; the panic check's zombie wears a helmet
    out = {"mode": "behaviour"}
    mark = (bench.SERVER / "server.log").stat().st_size
    for i, state in enumerate(["off", "on", "off", "on"]):
        r.cmd(f"mtmc sensors {state}")
        r.cmd("mtmc sensors threads 0")
        # the default minimum (32 mobs with due sensors per level tick) keeps small setups on the
        # vanilla path; these setups are small, so lower it so "on" really runs the phase
        r.cmd("mtmc sensors min 1")
        out[f"run{i}_{state}"] = {"gold": gold(r, 0, 300 + 40 * i), "panic": panic(r, 0, 300 + 40 * i),
                                  "village": village(r, 100, 300 + 50 * i)}
        print(state, json.dumps(out[f"run{i}_{state}"]), flush=True)
    out["sensor_stats"] = r.cmd("mtmc sensors")
    r.cmd("mtmc sensors min 32")
    with (bench.SERVER / "server.log").open("rb") as f:
        f.seek(mark)
        out["log_problems"] = [l for l in f.read().decode(errors="replace").splitlines()
                               if re.search(r"Exception|/ERROR\]", l)][:5]
    r.cmd("mtmc sensors off")
    r.close()
    out["time"] = time.strftime("%Y-%m-%dT%H:%M:%S")
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["ab", "behaviour"])
    ap.add_argument("--variant", default="mtmc")
    ap.add_argument("--running", action="store_true")
    ap.add_argument("--scenario", choices=["piglins", "villagers"], default="piglins")
    ap.add_argument("--mobs", type=int, default=600)
    ap.add_argument("--settle", type=float, default=20)
    ap.add_argument("--minutes", type=float, default=6)
    ap.add_argument("--period", type=float, default=40)
    args = ap.parse_args()
    res = ab(args) if args.mode == "ab" else behaviour(args)
    print(json.dumps({k: v for k, v in res.items() if k != "samples"}, indent=1))
    with (LAB_DIR / "sensor_lab.jsonl").open("a") as f:
        f.write(json.dumps(res) + "\n")


if __name__ == "__main__":
    main()
