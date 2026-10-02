"""Region ticking lab: does it save tick time, and is it still correct?

    python lab/region_lab.py ab --pens 6 --mobs 3000                # /mtmc regions on/off A/B, same load
    python lab/region_lab.py tnt --sites 6 --trials 3                 # crater, chain, cannon in 6 regions at once
    python lab/region_lab.py chaos --pens 6 --minutes 5               # a zoo in every pen, sprinted, then checked

All of it runs in the Overworld, so dimension parallelism doesn't help: whatever is gained is
region ticking. Every scenario starts the server with the `mtmcr` variant (regions on) unless
--running is given, and sets `/mtmc regions min 0` so even small loads use regions.

ab: bench.py's pens, `--pens` of them `--pen-spacing` blocks apart in a row (each pen is its own
region), `--mobs` animals shared out over them. Then /mtmc regions on and off alternate every
--period seconds; /tick query is sampled at the end of each period.

tnt: tnt_test.py's crater, chain and cannon, built at --sites places 256 blocks apart and set off
on the same tick, so the explosions run on different region threads (each one takes the level
exclusively). Pass: every crater drops exactly the blocks it destroyed, every chain uses up all
its TNT, and every cannon ends in the same state as with regions off (the cannon has no
randomness).

chaos: in every pen villagers with beds and job sites, zombies, skeletons, creepers, endermen,
sheep on grass, chickens, wolves, iron golems, items and TNT, run sprinting. Pass: the server
is alive, nothing logged an exception or an entity-section warning, the region counters show
no escapes and the mod no foreign chunk access.

Results: stdout and $LAB_DIR/region_lab.jsonl.
"""
from __future__ import annotations

import argparse
import hashlib
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
SERVER = bench.SERVER
OW = "overworld"
BAD_LOG = re.compile(r"Exception|wasn't found in section|marked as removed already|UUID of added entity already exists"
                     r"|\[diag\]|moved into another region|Error executing task|ERROR\]")


def r_cmd(r: Rcon, c: str) -> str:
    out = r.cmd(c)
    if any(w in out for w in ("Unknown", "Incorrect", "Expected", "Invalid", "Can't")):
        print(f"  ! {c} -> {out}")
    return out


def log_offset() -> int:
    return (SERVER / "server.log").stat().st_size


def log_problems(since: int) -> list[str]:
    with (SERVER / "server.log").open("rb") as f:
        f.seek(since)
        text = f.read().decode(errors="replace")
    return [line for line in text.splitlines() if BAD_LOG.search(line)][:30]


def regions_stats(r: Rcon) -> dict:
    out = r.cmd("mtmc regions")
    m = re.search(r"So far: (\{.*\})", out, re.S)
    try:
        return json.loads(m.group(1)) if m else {}
    except ValueError:
        return {"raw": out[-400:]}


def start(args) -> Rcon:
    if not args.running:
        bench.start(args.variant)
    r = Rcon(password="mtmclab")
    bench.gamerule(r, "max_entity_cramming", "maxEntityCramming", "0")
    bench.gamerule(r, "spawn_mobs", "doMobSpawning", "false")
    bench.gamerule(r, "advance_time", "doDaylightCycle", "false")
    r.cmd("time set noon")
    r.cmd("mtmc regions min 0")
    r.cmd("mtmc regions reset")
    return r


# ── ab ───────────────────────────────────────────────────────────────
def ab(args) -> dict:
    r = start(args)
    rng = random.Random(args.seed)
    centers = bench.pen_centers(args.pens, args.pen_spacing)
    for cx in centers:
        bench.build_pen(r, OW, cx)
    for k, cx in enumerate(centers):
        bench.summon(r, OW, args.mobs // len(centers) + (1 if k < args.mobs % len(centers) else 0), args.mix, rng, cx)
    time.sleep(args.settle)
    since = log_offset()
    samples = {"on": [], "off": []}
    mode = "on"
    for _ in range(args.rounds * 2):
        r.cmd(f"mtmc regions {mode}")
        time.sleep(args.period)
        q = bench.tick_query(r)
        samples[mode].append(q)
        mode = "off" if mode == "on" else "on"
    r.cmd("mtmc regions on")
    stats = regions_stats(r)
    res = {"scenario": "ab", "pens": args.pens, "mobs": args.mobs, "mix": args.mix, "cpus": bench.host_info(),
           "mspt_on": med(samples["on"], "avg"), "mspt_off": med(samples["off"], "avg"),
           "p95_on": med(samples["on"], "P95"), "p95_off": med(samples["off"], "P95"),
           "samples": samples, "regions": stats, "problems": log_problems(since)}
    if res["mspt_on"] and res["mspt_off"]:
        res["speedup"] = round(res["mspt_off"] / res["mspt_on"], 2)
    return res


def med(samples: list[dict], key: str):
    vals = [s[key] for s in samples if s.get(key) is not None]
    return round(statistics.median(vals), 2) if vals else None


# ── tnt ──────────────────────────────────────────────────────────────
CUBE = (-7, 223, -7, 7, 237, 7)
CENTER = (0, 230, 0)
BOX = (-40, 205, -40, -27, 214, -27)
TNTS = (-38, 206, -38, -29, 208, -29)
LANE = (10, 219, -6, 70, 219, 6)
TAG = "mtmc_rtnt"


class Sites:
    def __init__(self, r: Rcon, n: int, spacing: int):
        self.r = r
        self.xs = [i * spacing for i in range(n)]

    def ex(self, c: str) -> str:
        return r_cmd(self.r, f"execute in minecraft:overworld run {c}")

    def count_blocks(self, ox: int, box, block: str) -> int:
        x1, y1, z1, x2, y2, z2 = box
        dest = (ox + 30, 150, 60)
        out = self.ex(f"clone {ox + x1} {y1} {z1} {ox + x2} {y2} {z2} {dest[0]} {dest[1]} {dest[2]} filtered minecraft:{block} force")
        m = re.search(r"cloned (\d+)", out)
        self.ex(f"fill {dest[0]} {dest[1]} {dest[2]} {dest[0] + x2 - x1} {dest[1] + y2 - y1} {dest[2] + z2 - z1} minecraft:air")
        return int(m.group(1)) if m else 0

    def count_entities(self, ox: int, selector: str) -> int:
        out = self.r.cmd(f"execute in minecraft:overworld positioned {ox} 220 0 if entity {selector}")
        m = re.search(r"Count: (\d+)", out)
        return int(m.group(1)) if m else 0

    def dirt_items(self, ox: int) -> int:
        sel = '@e[type=minecraft:item,distance=..60,nbt={Item:{id:"minecraft:dirt"}}]'
        p = f"execute in minecraft:overworld positioned {ox + CENTER[0]} {CENTER[1]} {CENTER[2]}"
        self.r.cmd(f"{p} as {sel} store result score @s mtmc_cnt run data get entity @s Item.count")
        self.r.cmd("scoreboard players set #sum mtmc_cnt 0")
        self.r.cmd(f"{p} as {sel} run scoreboard players operation #sum mtmc_cnt += @s mtmc_cnt")
        m = re.search(r"has (-?\d+)", self.r.cmd("scoreboard players get #sum mtmc_cnt"))
        return int(m.group(1)) if m else -1

    def setup(self) -> None:
        bench.gamerule(self.r, "tnt_explosion_drop_decay", "tntExplosionDropDecay", "false")
        self.r.cmd("scoreboard objectives add mtmc_cnt dummy")
        for ox in self.xs:
            self.ex(f"forceload add {ox - 64} -64 {ox + 79} 63")
        time.sleep(3)

    def clear(self) -> None:
        for ox in self.xs:
            self.ex(f"kill @e[type=!minecraft:player,type=!minecraft:item,x={ox},y=220,z=0,distance=..110]")
            self.ex(f"kill @e[type=minecraft:item,x={ox},y=220,z=0,distance=..110]")

    def crater(self) -> dict:
        self.r.cmd("tick freeze")
        self.clear()
        x1, y1, z1, x2, y2, z2 = CUBE
        for ox in self.xs:
            self.ex(f"fill {ox + x1 - 2} {y1 - 2} {z1 - 2} {ox + x2 + 2} {y2 + 8} {z2 + 2} minecraft:air")
            self.ex(f"fill {ox + x1} {y1} {z1} {ox + x2} {y2} {z2} minecraft:dirt")
            self.ex(f"setblock {ox + CENTER[0]} {CENTER[1]} {CENTER[2]} minecraft:air")
            self.ex(f"summon minecraft:tnt {ox + CENTER[0] + .5} {CENTER[1]} {CENTER[2] + .5} {{fuse:1,Tags:[\"{TAG}\"]}}")
        before = 15 ** 3 - 1
        self.r.cmd("tick unfreeze")
        time.sleep(4)
        out = {}
        for ox in self.xs:
            destroyed = before - self.count_blocks(ox, CUBE, "dirt")
            items = self.dirt_items(ox)
            out[str(ox)] = {"destroyed": destroyed, "dropped": items, "ok": destroyed > 0 and items == destroyed}
        out["ok"] = all(v["ok"] for v in out.values() if isinstance(v, dict))
        return out

    def chain(self) -> dict:
        self.r.cmd("tick freeze")
        self.clear()
        bx1, by1, bz1, bx2, by2, bz2 = BOX
        tx1, ty1, tz1, tx2, ty2, tz2 = TNTS
        for ox in self.xs:
            self.ex(f"fill {ox + bx1} {by1} {bz1} {ox + bx2} {by2} {bz2} minecraft:obsidian hollow")
            self.ex(f"fill {ox + tx1} {ty1} {tz1} {ox + tx2} {ty2} {tz2} minecraft:tnt")
            cx, cz = ox + (tx1 + tx2) / 2 + .5, (tz1 + tz2) / 2 + .5
            self.ex(f"summon minecraft:tnt {cx} {ty2 + 2} {cz} {{fuse:1,Tags:[\"{TAG}\"]}}")
        self.r.cmd("tick unfreeze")
        time.sleep(12)
        out = {}
        for ox in self.xs:
            left = self.count_blocks(ox, BOX, "tnt")
            primed = self.count_entities(ox, "@e[type=minecraft:tnt,distance=..120]")
            out[str(ox)] = {"tnt_blocks_left": left, "primed_left": primed, "ok": left == 0 and primed == 0}
        out["ok"] = all(v["ok"] for v in out.values() if isinstance(v, dict))
        return out

    def cannon(self) -> dict:
        self.r.cmd("tick freeze")
        self.clear()
        x1, y1, z1, x2, y2, z2 = LANE
        for ox in self.xs:
            self.ex(f"fill {ox + x1} {y1 + 1} {z1} {ox + x2} {y1 + 12} {z2} minecraft:air")
            self.ex(f"fill {ox + x1} {y1} {z1} {ox + x2} {y2} {z2} minecraft:barrier")
            for _ in range(8):
                self.ex(f"summon minecraft:tnt {ox + 14.5} 220 0.5 {{fuse:1,Tags:[\"{TAG}\"]}}")
            self.ex(f"summon minecraft:tnt {ox + 16.5} 220 0.5 {{fuse:80,Tags:[\"{TAG}\",\"proj\"]}}")
            for i, (px, pz) in enumerate([(21.5, 0.5), (20.5, -4.5), (21.5, 3.5)]):
                self.ex(f"summon minecraft:pig {ox + px} 220 {pz} {{NoAI:1b,PersistenceRequired:1b,Health:200f,"
                        f"attributes:[{{id:\"minecraft:max_health\",base:200}}],Tags:[\"{TAG}\",\"pig{i}\"]}}")
        self.r.cmd("tick step 60")
        time.sleep(6)
        out = {}
        for ox in self.xs:
            def get(sel: str, path: str) -> str:
                txt = self.r.cmd(f"execute in minecraft:overworld positioned {ox + 20} 220 0 run data get entity {sel} {path}")
                return txt.split("data:", 1)[1].strip() if "data:" in txt else "missing"

            def rel(pos: str) -> str:
                # positions relative to the site, so all sites compare equal
                m = re.findall(r"(-?[\d.E-]+)d", pos)
                if len(m) != 3:
                    return pos
                return "[%s, %s, %s]" % (repr(float(m[0]) - ox), m[1], m[2])

            state = {"proj_pos": rel(get("@e[tag=proj,distance=..60,limit=1]", "Pos")),
                     "proj_motion": get("@e[tag=proj,distance=..60,limit=1]", "Motion")}
            for i in range(3):
                state[f"pig{i}_pos"] = rel(get(f"@e[tag=pig{i},distance=..60,limit=1]", "Pos"))
                state[f"pig{i}_health"] = get(f"@e[tag=pig{i},distance=..60,limit=1]", "Health")
            out[str(ox)] = state
        self.r.cmd("tick unfreeze")
        sigs = {k: hashlib.sha1(json.dumps(v, sort_keys=True).encode()).hexdigest()[:12] for k, v in out.items()}
        # absolute coordinates change the float rounding, so sites differ from each other; each site is
        # compared with itself between regions on and off (tnt()), and the projectile must have flown
        moved = all(v["proj_pos"] != "missing" and not v["proj_pos"].startswith("[16.5,") for v in out.values())
        return {"ok": moved, "signatures": sigs, "state0": out[str(self.xs[0])]}


def tnt(args) -> dict:
    r = start(args)
    s = Sites(r, args.sites, args.site_spacing)
    s.setup()
    since = log_offset()
    trials = []
    for mode in (["on", "off"] if args.compare else ["on"]):
        r.cmd(f"mtmc regions {mode}")
        for _ in range(args.trials):
            trials.append({"regions": mode, "crater": s.crater(), "chain": s.chain(), "cannon": s.cannon()})
    # every site's cannon must end the same in every trial, regions on or off
    per_site = {ox: {t["cannon"]["signatures"][ox] for t in trials} for ox in trials[0]["cannon"]["signatures"]}
    res = {"scenario": "tnt", "sites": args.sites, "trials": trials, "regions": regions_stats(r),
           "cannon_same_on_off": all(len(v) == 1 for v in per_site.values()), "problems": log_problems(since)}
    res["ok"] = all(t["crater"]["ok"] and t["chain"]["ok"] and t["cannon"]["ok"] for t in trials) \
        and res["cannon_same_on_off"] and not res["problems"]
    return res


# ── chaos ────────────────────────────────────────────────────────────
ZOO = [("villager", 10), ("zombie", 6), ("skeleton", 4), ("creeper", 3), ("enderman", 2), ("sheep", 10), ("chicken", 10),
       ("wolf", 4), ("iron_golem", 2), ("cow", 6), ("pig", 6), ("bee", 4), ("fox", 2), ("cat", 2), ("witch", 2), ("pillager", 2)]


def chaos(args) -> dict:
    r = start(args)
    rng = random.Random(args.seed)
    centers = bench.pen_centers(args.pens, args.pen_spacing)
    H = bench.HALF
    for cx in centers:
        bench.build_pen(r, OW, cx)
        ex = "execute in minecraft:overworld run "
        # roof, so the undead don't burn and nothing flies out; some dirt for endermen, job sites and beds for POI
        for x0 in range(-H - 1, H + 1, 20):
            r_cmd(r, ex + f"fill {cx + x0} {bench.PEN_Y + 6} {-H - 1} {cx + min(x0 + 19, H)} {bench.PEN_Y + 6} {H} minecraft:glass")
        r_cmd(r, ex + f"fill {cx - H} {bench.PEN_Y + 4} {-H} {cx + H - 1} {bench.PEN_Y + 5} {H - 1} minecraft:air")
        r_cmd(r, ex + f"fill {cx - H - 1} {bench.PEN_Y + 1} {-H - 1} {cx + H} {bench.PEN_Y + 5} {-H - 1} minecraft:glass")
        r_cmd(r, ex + f"fill {cx - H - 1} {bench.PEN_Y + 1} {H} {cx + H} {bench.PEN_Y + 5} {H} minecraft:glass")
        r_cmd(r, ex + f"fill {cx - H - 1} {bench.PEN_Y + 1} {-H - 1} {cx - H - 1} {bench.PEN_Y + 5} {H} minecraft:glass")
        r_cmd(r, ex + f"fill {cx + H} {bench.PEN_Y + 1} {-H - 1} {cx + H} {bench.PEN_Y + 5} {H} minecraft:glass")
        for i, block in enumerate(["composter", "lectern", "smoker", "barrel", "blast_furnace", "cartography_table",
                                   "brewing_stand", "fletching_table", "loom", "stonecutter"]):
            r_cmd(r, ex + f"setblock {cx - 30 + i * 6} {bench.PEN_Y + 1} -30 minecraft:{block}")
            r_cmd(r, ex + f"setblock {cx - 30 + i * 6} {bench.PEN_Y + 1} 30 minecraft:red_bed[facing=north,part=foot]")
            r_cmd(r, ex + f"setblock {cx - 30 + i * 6} {bench.PEN_Y + 1} 29 minecraft:red_bed[facing=north,part=head]")
        r_cmd(r, ex + f"setblock {cx} {bench.PEN_Y + 1} 0 minecraft:bell")
        r_cmd(r, ex + f"setblock {cx + 10} {bench.PEN_Y + 1} 10 minecraft:bee_nest")
        for k in range(8):
            r_cmd(r, ex + f"setblock {cx - 35 + k * 9} {bench.PEN_Y + 1} 20 minecraft:poppy")
            r_cmd(r, ex + f"setblock {cx - 35 + k * 9} {bench.PEN_Y + 1} 15 minecraft:sweet_berry_bush[age=3]")
    for cx in centers:
        for mob, n in ZOO:
            for _ in range(n * args.zoo_scale):
                x, z = cx + rng.uniform(-H + 2, H - 3), rng.uniform(-H + 2, H - 3)
                nbt = "{PersistenceRequired:1b}"
                if mob == "wolf":
                    nbt = "{PersistenceRequired:1b,Owner:[I;1,2,3,4]}"
                r_cmd(r, f"execute in minecraft:overworld run summon minecraft:{mob} {x:.2f} {bench.PEN_Y + 1} {z:.2f} {nbt}")
        # loose items and TNT that will go off during the run
        r_cmd(r, f"execute in minecraft:overworld run summon minecraft:item {cx + 5} {bench.PEN_Y + 2} 5 {{Item:{{id:\"minecraft:wheat\",count:32}}}}")
        r_cmd(r, f"execute in minecraft:overworld run summon minecraft:item {cx - 5} {bench.PEN_Y + 2} -5 {{Item:{{id:\"minecraft:bread\",count:16}}}}")
        for t in range(3):
            r_cmd(r, f"execute in minecraft:overworld run summon minecraft:tnt {cx - 20 + t * 20} {bench.PEN_Y + 1} -20 {{fuse:{200 + t * 400}}}")
    counts_before = {cx: count_in_pen(r, cx) for cx in centers}
    since = log_offset()
    r.cmd("mtmc regions on")
    t0 = time.time()
    ticks = int(args.minutes * 60 * 20)
    sp = bench.sprint(r, ticks, timeout=args.minutes * 60 * 4 + 120)
    counts_after = {cx: count_in_pen(r, cx) for cx in centers}
    stats = regions_stats(r)
    mtmc = bench.mtmc_stats()
    problems = log_problems(since)
    foreign = {k: v for k, v in (mtmc or {}).get("cross_level", {}).items() if k.startswith("diag:")}
    res = {"scenario": "chaos", "pens": args.pens, "zoo_scale": args.zoo_scale, "sprint": sp, "wall_s": round(time.time() - t0, 1),
           "counts_before": counts_before, "counts_after": counts_after, "regions": stats, "diag": foreign,
           "problems": problems}
    res["ok"] = (sp.get("tps") is not None and not problems and not foreign and stats.get("escapes", 1) == 0
                 and stats.get("phases", 0) > 0)
    return res


def count_in_pen(r: Rcon, cx: int) -> dict:
    out = {}
    for t in ("villager", "zombie", "sheep", "chicken", "item", "iron_golem", "wolf"):
        txt = r.cmd(f"execute in minecraft:overworld positioned {cx} {bench.PEN_Y} 0 if entity @e[type=minecraft:{t},distance=..70]")
        m = re.search(r"Count: (\d+)", txt)
        out[t] = int(m.group(1)) if m else 0
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("scenario", choices=["ab", "tnt", "chaos"])
    ap.add_argument("--variant", default="mtmcr")
    ap.add_argument("--running", action="store_true", help="use the server that is up")
    ap.add_argument("--pens", type=int, default=6)
    ap.add_argument("--pen-spacing", type=int, default=256)
    ap.add_argument("--mobs", type=int, default=3000)
    ap.add_argument("--mix", nargs="+", default=["cow", "sheep", "chicken", "pig"])
    ap.add_argument("--settle", type=float, default=15)
    ap.add_argument("--period", type=float, default=20)
    ap.add_argument("--rounds", type=int, default=4)
    ap.add_argument("--sites", type=int, default=6)
    ap.add_argument("--site-spacing", type=int, default=256)
    ap.add_argument("--trials", type=int, default=2)
    ap.add_argument("--compare", action="store_true", help="tnt: also run every trial with regions off")
    ap.add_argument("--minutes", type=float, default=3)
    ap.add_argument("--zoo-scale", type=int, default=1)
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args()
    res = {"ab": ab, "tnt": tnt, "chaos": chaos}[args.scenario](args)
    res["time"] = time.strftime("%Y-%m-%dT%H:%M:%S")
    res["variant"] = "(running)" if args.running else args.variant
    res["host"] = bench.host_info()
    summary = {k: v for k, v in res.items() if k not in ("samples", "trials")}
    print(json.dumps(summary, indent=1)[:6000])
    if "trials" in res:
        for t in res["trials"]:
            print(json.dumps({"regions": t["regions"], "crater_ok": t["crater"]["ok"], "chain_ok": t["chain"]["ok"],
                              "cannon_ok": t["cannon"]["ok"], "cannon_sigs": list(t["cannon"]["signatures"].values())}))
    with (LAB_DIR / "region_lab.jsonl").open("a") as f:
        f.write(json.dumps(res) + "\n")
    if not args.running:
        import subprocess
        subprocess.run([str(HERE / "stop_server.sh"), str(LAB_DIR)], check=False)


if __name__ == "__main__":
    main()
