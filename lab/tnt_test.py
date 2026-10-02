"""TNT correctness: blocks broken, drops, knockback/damage, chain reactions, client sync.

    python lab/tnt_test.py --variant mtmc --trials 5            # restarts the server
    python lab/tnt_test.py --running --trials 5 --bot           # use the server (and bot) that is up

Every trial runs the same setups in the Overworld, the Nether and the End and starts them on
the same tick (built under /tick freeze), so with MultithreadMC three workers explode at once.

* crater: a solid 15x15x15 dirt cube, one TNT in the middle. With
  tnt_explosion_drop_decay off every destroyed block drops, so
  dropped dirt == destroyed blocks, exactly. The destroyed count itself is random
  (explosion rays), so it is compared as a distribution between variants.
* chain: 300 TNT blocks in a sealed obsidian box, one lit. Every TNT must be used up:
  no TNT blocks and no primed TNT left. Records the worst tick (MSPT) while it goes off.
* cannon: on blast-proof barrier blocks, 8 TNT go off next to a TNT "projectile" and three
  no-AI 200 HP pigs, run with /tick step. Knockback and damage don't use randomness, so the
  projectile's position and the pigs' positions and health after 60 ticks must be identical
  in all three dimensions and identical between variants (compare `cannon_signature`).
* sync (--bot, Overworld): the blocks the bot's client sees in the crater cube (scan_area)
  must equal the server's, block for block.

Results: stdout and $LAB_DIR/tnt.jsonl.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import bench  # noqa: E402
from rcon import Rcon  # noqa: E402

LAB_DIR = bench.LAB_DIR
SERVER = bench.SERVER
DIMS = ["overworld", "the_nether", "the_end"]
TAG = "mtmc_tnt"

# crater cube, centre, and a scratch area to count blocks into (clone ... filtered)
CUBE = (-7, 223, -7, 7, 237, 7)
CENTER = (0, 230, 0)
SCRATCH = (30, 223, -40)
# chain: sealed obsidian box, 10x10x3 TNT inside
BOX = (-40, 205, -40, -27, 214, -27)
TNT = (-38, 206, -38, -29, 208, -29)
SCRATCH2 = (30, 205, 30)
# cannon lane on barrier
LANE = (10, 219, -6, 70, 219, 6)


class Tnt:
    def __init__(self, args):
        self.args = args
        self.r = Rcon(password="mtmclab")
        self.emma = None

    def cmd(self, c: str) -> str:
        out = self.r.cmd(c)
        if any(w in out for w in ("Unknown", "Incorrect", "Expected", "Invalid", "Can't")):
            print(f"  ! {c} -> {out}")
        return out

    def ex(self, dim: str, c: str) -> str:
        return self.cmd(f"execute in minecraft:{dim} run {c}")

    def count_blocks(self, dim: str, box, block: str, dest) -> int:
        x1, y1, z1, x2, y2, z2 = box
        out = self.ex(dim, f"clone {x1} {y1} {z1} {x2} {y2} {z2} {dest[0]} {dest[1]} {dest[2]} filtered minecraft:{block} force")
        m = re.search(r"cloned (\d+)", out)
        dx, dy, dz = dest
        self.ex(dim, f"fill {dx} {dy} {dz} {dx + x2 - x1} {dy + y2 - y1} {dz + z2 - z1} minecraft:air")
        return int(m.group(1)) if m else 0

    def count_entities(self, dim: str, selector: str) -> int:
        out = self.r.cmd(f"execute in minecraft:{dim} positioned 0 220 0 if entity {selector}")
        m = re.search(r"Count: (\d+)", out)
        return int(m.group(1)) if m else 0

    def item_total(self, dim: str) -> int:
        sel = '@e[type=minecraft:item,distance=..60,nbt={Item:{id:"minecraft:dirt"}}]'
        p = f"execute in minecraft:{dim} positioned {CENTER[0]} {CENTER[1]} {CENTER[2]}"
        self.cmd(f"{p} as {sel} store result score @s mtmc_cnt run data get entity @s Item.count")
        self.cmd("scoreboard players set #sum mtmc_cnt 0")
        self.cmd(f"{p} as {sel} run scoreboard players operation #sum mtmc_cnt += @s mtmc_cnt")
        m = re.search(r"has (-?\d+)", self.r.cmd("scoreboard players get #sum mtmc_cnt"))
        return int(m.group(1)) if m else -1

    def mspt(self) -> dict:
        return bench.tick_query(self.r)

    def setup(self) -> None:
        bench.gamerule(self.r, "tnt_explosion_drop_decay", "tntExplosionDropDecay", "false")
        bench.gamerule(self.r, "spawn_mobs", "doMobSpawning", "false")
        bench.gamerule(self.r, "max_entity_cramming", "maxEntityCramming", "0")
        self.cmd("scoreboard objectives add mtmc_cnt dummy")
        for d in DIMS:
            self.ex(d, "forceload add -64 -64 63 63")
        time.sleep(3)

    def clear(self) -> None:
        # mobs first, then items: killed pigs drop porkchops, which an earlier version of this
        # test counted into the next crater's drops
        for d in DIMS:
            self.ex(d, "kill @e[type=!minecraft:player,type=!minecraft:item,x=0,y=220,z=0,distance=..120]")
            self.ex(d, "kill @e[type=minecraft:item,x=0,y=220,z=0,distance=..120]")

    # ── scenarios ────────────────────────────────────────────────────
    def crater(self) -> dict:
        self.cmd("tick freeze")
        self.clear()
        x1, y1, z1, x2, y2, z2 = CUBE
        for d in DIMS:
            self.ex(d, f"fill {x1 - 2} {y1 - 2} {z1 - 2} {x2 + 2} {y2 + 8} {z2 + 2} minecraft:air")
            self.ex(d, f"fill {x1} {y1} {z1} {x2} {y2} {z2} minecraft:dirt")
            self.ex(d, f"setblock {CENTER[0]} {CENTER[1]} {CENTER[2]} minecraft:air")
            self.ex(d, f"summon minecraft:tnt {CENTER[0] + .5} {CENTER[1]} {CENTER[2] + .5} {{fuse:1,Tags:[\"{TAG}\"]}}")
        before = 15 ** 3 - 1
        self.cmd("tick unfreeze")
        time.sleep(4)
        out = {}
        for d in DIMS:
            remaining = self.count_blocks(d, CUBE, "dirt", SCRATCH)
            destroyed = before - remaining
            items = self.item_total(d)
            out[d] = {"destroyed": destroyed, "dropped": items, "ok": destroyed > 0 and items == destroyed}
        if self.emma is not None:
            out["sync_overworld"] = self.sync_check()
        out["ok"] = all(v["ok"] for v in out.values() if isinstance(v, dict))
        return out

    def sync_check(self) -> dict:
        """Client view of the crater cube vs the server's, block for block (Overworld)."""
        time.sleep(1)
        res = self.emma.scan_area(CENTER[0], CENTER[1], CENTER[2], radius=7, include_all=True, timeout=20)
        blocks = (res.get("data") or res).get("blocks", [])  # bridge replies {id, type, status, data: {blocks}}
        client = {(b["x"], b["y"], b["z"]) for b in blocks if b.get("block_type") == "minecraft:dirt"}
        server = set()
        x1, y1, z1, x2, y2, z2 = CUBE
        for x in range(x1, x2 + 1):
            for y in range(y1, y2 + 1):
                for z in range(z1, z2 + 1):
                    if "passed" in self.r.cmd(f"execute in minecraft:overworld if block {x} {y} {z} minecraft:dirt"):
                        server.add((x, y, z))
        return {"ok": client == server, "server_dirt": len(server), "client_dirt": len(client),
                "only_server": sorted(server - client)[:10], "only_client": sorted(client - server)[:10]}

    def chain(self) -> dict:
        self.cmd("tick freeze")
        self.clear()
        bx1, by1, bz1, bx2, by2, bz2 = BOX
        tx1, ty1, tz1, tx2, ty2, tz2 = TNT
        for d in DIMS:
            self.ex(d, f"fill {bx1} {by1} {bz1} {bx2} {by2} {bz2} minecraft:obsidian hollow")
            self.ex(d, f"fill {tx1} {ty1} {tz1} {tx2} {ty2} {tz2} minecraft:tnt")
            cx, cz = (tx1 + tx2) / 2 + .5, (tz1 + tz2) / 2 + .5
            self.ex(d, f"summon minecraft:tnt {cx} {ty2 + 2} {cz} {{fuse:1,Tags:[\"{TAG}\"]}}")
        placed = (tx2 - tx1 + 1) * (ty2 - ty1 + 1) * (tz2 - tz1 + 1)
        self.cmd("tick unfreeze")
        worst = 0.0
        t0 = time.time()
        while time.time() - t0 < 12:
            time.sleep(2)
            q = self.mspt()
            worst = max(worst, q.get("P99") or 0, q.get("avg") or 0)
        out = {"placed_per_dim": placed, "worst_mspt_p99": worst}
        for d in DIMS:
            left_blocks = self.count_blocks(d, BOX, "tnt", SCRATCH2)
            primed = self.count_entities(d, "@e[type=minecraft:tnt,distance=..120]")
            intact = self.count_blocks(d, BOX, "obsidian", SCRATCH2)
            out[d] = {"tnt_blocks_left": left_blocks, "primed_left": primed, "obsidian_shell": intact,
                      "ok": left_blocks == 0 and primed == 0}
        out["ok"] = all(out[d]["ok"] for d in DIMS)
        return out

    def cannon(self) -> dict:
        self.cmd("tick freeze")
        self.clear()
        x1, y1, z1, x2, y2, z2 = LANE
        for d in DIMS:
            self.ex(d, f"fill {x1} {y1 + 1} {z1} {x2} {y1 + 12} {z2} minecraft:air")
            self.ex(d, f"fill {x1} {y1} {z1} {x2} {y2} {z2} minecraft:barrier")
            for _ in range(8):
                self.ex(d, f"summon minecraft:tnt 14.5 220 0.5 {{fuse:1,Tags:[\"{TAG}\"]}}")
            self.ex(d, f"summon minecraft:tnt 16.5 220 0.5 {{fuse:80,Tags:[\"{TAG}\",\"proj\"]}}")
            # 200 HP, 7.0-7.9 blocks from the charge: all take knockback and damage, none die
            for i, (px, pz) in enumerate([(21.5, 0.5 + 0.0), (20.5, -4.5), (21.5, 3.5)]):
                self.ex(d, f"summon minecraft:pig {px} 220 {pz} {{NoAI:1b,PersistenceRequired:1b,Health:200f,"
                           f"attributes:[{{id:\"minecraft:max_health\",base:200}}],Tags:[\"{TAG}\",\"pig{i}\"]}}")
        self.cmd("tick step 60")
        time.sleep(6)
        out = {}
        for d in DIMS:
            def get(sel: str, path: str) -> str:
                txt = self.r.cmd(f"execute in minecraft:{d} positioned 20 220 0 run data get entity {sel} {path}")
                return txt.split("data:", 1)[1].strip() if "data:" in txt else "missing"
            state = {"proj_pos": get("@e[tag=proj,distance=..200,limit=1]", "Pos"),
                     "proj_motion": get("@e[tag=proj,distance=..200,limit=1]", "Motion")}
            for i in range(3):
                state[f"pig{i}_pos"] = get(f"@e[tag=pig{i},distance=..200,limit=1]", "Pos")
                state[f"pig{i}_health"] = get(f"@e[tag=pig{i},distance=..200,limit=1]", "Health")
            out[d] = state
        self.cmd("tick unfreeze")
        sigs = {d: hashlib.sha1(json.dumps(out[d], sort_keys=True).encode()).hexdigest()[:12] for d in DIMS}
        moved = out["overworld"]["proj_pos"] not in ("missing", "[16.5d, 220.0d, 0.5d]")
        return {"ok": len(set(sigs.values())) == 1 and moved, "cannon_signature": sigs["overworld"],
                "signatures": sigs, "state": out}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--variant", default="mtmc")
    ap.add_argument("--running", action="store_true", help="use the server that is up (no restart)")
    ap.add_argument("--trials", type=int, default=5)
    ap.add_argument("--bot", action="store_true", help="client sync check with the bot (must be connected)")
    ap.add_argument("--scenarios", nargs="+", default=["crater", "chain", "cannon"])
    args = ap.parse_args()
    if not args.running:
        bench.start(args.variant)
    t = Tnt(args)
    if args.bot:
        sys.path.insert(0, str(Path(__import__("os").environ.get("EMMA_DIR", LAB_DIR / "emma262"))))
        import gamer
        gamer.configure({"player_port": 8765, "db_path": str(LAB_DIR / "bot" / "minecraft.db"),
                         "auto_idle_delay_seconds": 3600, "auto_idle": False})
        gamer.init_db()
        from gamer.emmatone_client import connect
        t.emma = connect()
        deadline = time.time() + 20
        while not t.emma.connected and time.time() < deadline:
            time.sleep(0.2)
        t.emma.set_mode("stop")
        t.cmd("gamemode spectator EmmaBot")  # watches, can't be hit or interfere
        t.cmd("execute in minecraft:overworld run tp EmmaBot 0 236 20 180 30")
    t.setup()
    variant = (SERVER / "variant.txt").read_text().strip()
    log_mark = (SERVER / "server.log").stat().st_size
    rows = []
    for trial in range(args.trials):
        for name in args.scenarios:
            res = getattr(t, name)()
            res.update({"scenario": name, "trial": trial, "variant": variant, "time": time.strftime("%Y-%m-%dT%H:%M:%S")})
            rows.append(res)
            short = {k: v for k, v in res.items() if k not in ("state",)}
            print(json.dumps(short)[:600], flush=True)
            with (LAB_DIR / "tnt.jsonl").open("a") as f:
                f.write(json.dumps(res) + "\n")
    with (SERVER / "server.log").open("rb") as f:
        f.seek(log_mark)
        problems = [l for l in f.read().decode(errors="replace").splitlines()
                    if re.search(r"Exception|/ERROR\]", l) and "Cross-dimension" not in l]
    craters = [r[d]["destroyed"] for r in rows if r["scenario"] == "crater" for d in DIMS]
    summary = {
        "variant": variant,
        "all_ok": all(r["ok"] for r in rows),
        "failures": [(r["scenario"], r["trial"]) for r in rows if not r["ok"]],
        "crater_destroyed_mean": round(statistics.mean(craters), 1) if craters else None,
        "crater_destroyed_sd": round(statistics.pstdev(craters), 1) if len(craters) > 1 else None,
        "cannon_signatures": sorted({r["cannon_signature"] for r in rows if r["scenario"] == "cannon"}),
        "chain_worst_mspt": max((r["worst_mspt_p99"] for r in rows if r["scenario"] == "chain"), default=None),
        "log_problems": problems[:10],
    }
    print("SUMMARY", json.dumps(summary))
    with (LAB_DIR / "tnt.jsonl").open("a") as f:
        f.write(json.dumps({"summary": summary}) + "\n")


if __name__ == "__main__":
    main()
