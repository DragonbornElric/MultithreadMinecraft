"""Stress and correctness run with the Emma bridge bot on a MultithreadMC server.

    python lab/stress_bot.py --variant mtmc --mobs 600 --scenarios all

Starts the server (fresh world, mob pens in all three dimensions, as bench.py), starts the
bot (bot/start_bot.sh) unless --bot-running, then runs scenarios that each go through one of
the cross-dimension paths the mod defers, while every dimension is busy:

* ``hero``: the bot plays (GOAP hero mode: mining, fighting, exploring = chunk loading and
  generation) in the Overworld while the pens tick in all three dimensions.
* ``nether_hero``: the same in the Nether.
* ``portal_walk``: the bot walks into a lit Nether portal and back (player portal path).
* ``portal_stream``: chickens are summoned into a portal; every one must arrive in the Nether
  (entity portal path, many per tick).
* ``pearl``: ender pearls whose owner (the bot) is in the other dimension: the bot must be
  pulled across each time (deferred ender pearl landing).
* ``command_blocks``: repeating command blocks in the Nether and the End that count into a
  scoreboard through ``execute in`` another dimension; the count must equal the game ticks
  that passed (a deferred command block still runs exactly once per tick).

Each scenario reports pass/fail, MSPT and the mod's deferred/cross-level counters; the server
log is checked for exceptions. Results are appended to ``$LAB_DIR/stress.jsonl``.
"""
from __future__ import annotations

import argparse
import json
import os
import re
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
EMMA_DIR = Path(os.environ.get("EMMA_DIR", LAB_DIR / "emma262"))
BOT = os.environ.get("BOT_NAME", "EmmaBot")
Y = bench.PEN_Y


class Stress:
    def __init__(self, args):
        self.args = args
        self.r = Rcon(password="mtmclab")
        self.emma = None

    # ── server helpers ───────────────────────────────────────────────
    def cmd(self, c: str) -> str:
        out = self.r.cmd(c)
        if any(w in out for w in ("Unknown", "Incorrect", "Expected", "Invalid")):
            print(f"  ! {c} -> {out}")
        return out

    def dim(self, who: str = BOT) -> str | None:
        m = re.search(r'"(minecraft:[a-z_]+)"', self.r.cmd(f"data get entity {who} Dimension"))
        return m.group(1) if m else None

    def count(self, dim: str, selector: str) -> int:
        out = self.r.cmd(f"execute in minecraft:{dim} if entity {selector}")
        m = re.search(r"Count: (\d+)", out)
        return int(m.group(1)) if m else 0

    def game_time(self) -> int:
        return int(re.search(r"(\d+)", self.r.cmd("time query gametime")).group(1))

    def log_mark(self) -> int:
        return (SERVER / "server.log").stat().st_size

    def log_exceptions(self, since: int = 0) -> list[str]:
        with (SERVER / "server.log").open("rb") as f:
            f.seek(since)
            txt = f.read().decode(errors="replace")
        return [l for l in txt.splitlines() if re.search(r"Exception|Error:|Crash|deadlock|Can't keep up", l)
                and "Cross-dimension access" not in l][-10:]

    def connect_bot(self) -> None:
        sys.path.insert(0, str(EMMA_DIR))
        import gamer
        gamer.configure({"player_port": 8765, "db_path": str(LAB_DIR / "bot" / "minecraft.db"),
                         "auto_idle_delay_seconds": 3600, "auto_idle": False})
        gamer.init_db()
        from gamer.emmatone_client import connect
        self.emma = connect()
        deadline = time.time() + 20
        while not self.emma.connected and time.time() < deadline:
            time.sleep(0.2)
        if not self.emma.connected:
            raise RuntimeError("bot bridge not reachable on ws://localhost:8765")

    def kit(self) -> None:
        self.cmd(f"op {BOT}")
        self.cmd(f"gamemode survival {BOT}")
        self.cmd(f"clear {BOT}")
        for item in ["diamond_sword", "diamond_pickaxe", "diamond_axe", "diamond_shovel", "shield",
                     "diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots",
                     "cooked_beef 64", "cobblestone 64", "cobblestone 64", "torch 64", "water_bucket"]:
            self.cmd(f"give {BOT} minecraft:{item}")
        self.cmd(f"effect give {BOT} minecraft:resistance infinite 2 true")
        self.cmd(f"effect give {BOT} minecraft:fire_resistance infinite 0 true")

    def tp(self, dim: str, x: float, y: float, z: float) -> None:
        self.cmd(f"execute in minecraft:{dim} run tp {BOT} {x} {y} {z}")

    def mspt(self) -> float | None:
        return bench.tick_query(self.r).get("avg")

    def stop_bot(self) -> None:
        try:
            self.emma.set_mode("stop")
        except Exception:
            pass

    # ── scenarios ────────────────────────────────────────────────────
    def hero(self, dimension: str = "overworld") -> dict:
        if dimension == "overworld":
            self.cmd(f"execute in minecraft:overworld run spreadplayers 300 300 0 40 false {BOT}")
        else:
            self.tp("the_nether", 300, 64, 300)  # below the roof: real Nether terrain
            self.cmd(f"execute in minecraft:the_nether positioned 300 64 300 run fill ~-2 ~-1 ~-2 ~2 ~-1 ~2 minecraft:netherrack")
            self.cmd(f"execute in minecraft:the_nether positioned 300 64 300 run fill ~-2 ~ ~-2 ~2 ~3 ~2 minecraft:air")
        time.sleep(3)
        self.emma.hero_mode("iron")
        samples, t0 = [], time.time()
        while time.time() - t0 < self.args.hero_seconds:
            time.sleep(10)
            samples.append(self.mspt())
        pos = self.emma.position
        self.stop_bot()
        m = re.search(r"data: ([\d.]+)f", self.r.cmd(f"data get entity {BOT} Health"))
        alive = bool(m) and float(m.group(1)) > 0
        return {"ok": alive, "mspt": samples, "bot_dimension": self.dim(), "bot_pos": pos}

    def nether_hero(self) -> dict:
        return self.hero("the_nether")

    def build_portal(self, dim: str, x: int, y: int, z: int) -> None:
        """Obsidian frame 4 wide x 5 tall in the x axis at (x..x+3, y..y+4, z), lit."""
        ex = f"execute in minecraft:{dim} run "
        self.cmd(ex + f"fill {x} {y} {z} {x + 3} {y + 4} {z} minecraft:obsidian")
        self.cmd(ex + f"fill {x + 1} {y + 1} {z} {x + 2} {y + 3} {z} minecraft:nether_portal[axis=x]")

    def portal_walk(self) -> dict:
        self.build_portal("overworld", 30, Y + 1, 30)
        trips, t0 = [], time.time()
        arrival = None
        if self.dim() != "minecraft:overworld":
            self.tp("overworld", 0.5, Y + 1, 0.5)
        for i in range(self.args.portal_trips):
            src = self.dim()
            if src == "minecraft:overworld":
                self.tp("overworld", 31.5, Y + 2, 33.5)
                time.sleep(1)
                self.emma.goto(31, Y + 2, 30)
            else:
                # step out of the portal she arrived in and back in
                pos = arrival
                self.cmd(f"execute in minecraft:the_nether run tp {BOT} {pos[0]:.2f} {pos[1] + 6:.2f} {pos[2]:.2f}")
                time.sleep(2)
                self.cmd(f"execute in minecraft:the_nether run tp {BOT} {pos[0]:.2f} {pos[1]:.2f} {pos[2]:.2f}")
            deadline = time.time() + 30
            while time.time() < deadline and self.dim() == src:
                time.sleep(0.5)
            arrived = self.dim()
            if arrived != src:
                self.stop_bot()  # the goto target is in the other dimension's coordinates
                arrival = [float(v) for v in re.findall(r"(-?[\d.]+)d", self.r.cmd(f"data get entity {BOT} Pos"))]
            trips.append({"from": src, "to": arrived, "ok": arrived != src, "s": round(30 - (deadline - time.time()), 1)})
            time.sleep(3)
        self.stop_bot()
        return {"ok": all(t["ok"] for t in trips) and len(trips) == self.args.portal_trips, "trips": trips,
                "seconds": round(time.time() - t0)}

    def portal_stream(self) -> dict:
        self.build_portal("overworld", -34, Y + 1, -30)
        self.cmd("kill @e[type=minecraft:chicken,tag=mtmc_stream]")
        sent = 0
        for _ in range(self.args.stream_waves):
            for i in range(self.args.stream_per_wave):
                self.cmd(f"execute in minecraft:overworld run summon minecraft:chicken {-32.5 + (i % 2)} {Y + 2} -29.5 "
                         "{Tags:[\"mtmc_stream\"],PersistenceRequired:1b}")
                sent += 1
            time.sleep(1)
        time.sleep(5)
        sel = "@e[type=minecraft:chicken,tag=mtmc_stream,x=0,y=0,z=0,distance=..100000]"
        arrived = self.count("the_nether", sel)
        left = self.count("overworld", sel)
        return {"ok": arrived == sent, "sent": sent, "arrived_in_nether": arrived, "still_in_overworld": left}

    def uuid(self) -> str:
        m = re.search(r"\[I; ?(-?\d+), ?(-?\d+), ?(-?\d+), ?(-?\d+)\]", self.r.cmd(f"data get entity {BOT} UUID"))
        return "[I;" + ",".join(m.groups()) + "]"

    def pearl(self) -> dict:
        self.stop_bot()
        owner = self.uuid()
        results = []
        for i in range(self.args.pearls):
            here = self.dim()
            if here == "minecraft:overworld":
                target = "the_nether"
                self.tp("overworld", 10.5, Y + 1, 10.5)
            else:
                target = "overworld"
                self.tp("the_nether", 10.5, Y + 1, 10.5)
            time.sleep(1)
            self.cmd(f"execute in minecraft:{target} run summon minecraft:ender_pearl -10.5 {Y + 6} -10.5 "
                     f"{{Owner:{owner},Motion:[0.0,-0.5,0.0]}}")
            want = f"minecraft:{target}"
            deadline = time.time() + 15
            while time.time() < deadline and self.dim() != want:
                time.sleep(0.5)
            got = self.dim()
            pos = re.findall(r"(-?[\d.]+)d", self.r.cmd(f"data get entity {BOT} Pos"))
            results.append({"to": want, "ok": got == want, "pos": pos})
            time.sleep(2)
        return {"ok": all(r["ok"] for r in results), "pearls": results}

    def command_blocks(self) -> dict:
        self.cmd("scoreboard objectives add mtmc dummy")
        self.cmd("scoreboard players set from_nether mtmc 0")
        self.cmd("scoreboard players set from_end mtmc 0")
        self.cmd("execute in minecraft:the_nether run setblock 20 201 -20 minecraft:repeating_command_block"
                 "{auto:1b,Command:\"execute in minecraft:overworld run scoreboard players add from_nether mtmc 1\"}")
        self.cmd("execute in minecraft:the_end run setblock 20 201 -20 minecraft:repeating_command_block"
                 "{auto:1b,Command:\"execute in minecraft:the_nether run scoreboard players add from_end mtmc 1\"}")
        # also something that really writes into another dimension's blocks every tick
        self.cmd("execute in minecraft:the_end run setblock 22 201 -20 minecraft:repeating_command_block"
                 "{auto:1b,Command:\"execute in minecraft:overworld run setblock 0 230 0 minecraft:stone\"}")
        time.sleep(2)

        def read() -> tuple[int, int, int]:
            # frozen, so the three reads see the same tick
            self.cmd("tick freeze")
            try:
                score = lambda who: int(re.search(r"has (-?\d+)", self.r.cmd(f"scoreboard players get {who} mtmc")).group(1))
                return self.game_time(), score("from_nether"), score("from_end")
            finally:
                self.cmd("tick unfreeze")

        g0, a0, b0 = read()
        time.sleep(self.args.command_seconds)
        g1, a1, b1 = read()
        ticks = g1 - g0
        ok = (a1 - a0) == ticks and (b1 - b0) == ticks
        for d in ("the_nether", "the_end"):
            self.cmd(f"execute in minecraft:{d} run fill 20 201 -20 22 201 -20 minecraft:air")
        return {"ok": ok, "game_ticks": ticks, "nether_block_runs": a1 - a0, "end_block_runs": b1 - b0}


SCENARIOS = ["command_blocks", "portal_stream", "pearl", "portal_walk", "hero", "nether_hero"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--variant", default="mtmc")
    ap.add_argument("--mobs", type=int, default=600, help="mobs per dimension pen (background load)")
    ap.add_argument("--scenarios", nargs="+", default=["all"])
    ap.add_argument("--bot-running", action="store_true", help="don't restart server/bot; use what is up")
    ap.add_argument("--hero-seconds", type=int, default=180)
    ap.add_argument("--portal-trips", type=int, default=4)
    ap.add_argument("--stream-waves", type=int, default=20)
    ap.add_argument("--stream-per-wave", type=int, default=10)
    ap.add_argument("--pearls", type=int, default=6)
    ap.add_argument("--command-seconds", type=int, default=30)
    args = ap.parse_args()
    names = SCENARIOS if args.scenarios == ["all"] else args.scenarios

    if not args.bot_running:
        subprocess.run([str(HERE / "bot" / "stop_bot.sh")], check=False)
        bench.start(args.variant)
        r = Rcon(password="mtmclab")
        bench.gamerule(r, "max_entity_cramming", "maxEntityCramming", "0")
        bench.gamerule(r, "spawn_mobs", "doMobSpawning", "false")
        import random
        rng = random.Random(1)
        for d in ["overworld", "the_nether", "the_end"]:
            bench.build_pen(r, d)
            bench.summon(r, d, args.mobs, ["cow", "sheep", "chicken", "pig"], rng)
        r.close()
        out = subprocess.run([str(HERE / "bot" / "start_bot.sh")], capture_output=True, text=True)
        print(out.stdout.strip(), out.stderr.strip()[-2000:])
        if out.returncode != 0:
            raise SystemExit("bot did not join")
    s = Stress(args)
    s.connect_bot()
    s.kit()
    variant = (SERVER / "variant.txt").read_text().strip()
    for name in names:
        t0 = time.time()
        mark = s.log_mark()
        try:
            res = getattr(s, name)()
        except Exception as e:  # a scenario failing must not hide the others
            res = {"ok": False, "error": repr(e)}
        res.update({"scenario": name, "variant": variant, "wall_s": round(time.time() - t0),
                    "mspt_after": s.mspt(), "server_alive": True, "log_problems": s.log_exceptions(mark),
                    "mtmc": bench.mtmc_stats(), "time": time.strftime("%Y-%m-%dT%H:%M:%S")})
        print(json.dumps({k: v for k, v in res.items() if k != "mtmc"}))
        if res["mtmc"]:
            print("   mtmc deferred:", res["mtmc"].get("deferred"), "cross_level:", res["mtmc"].get("cross_level"))
        with (LAB_DIR / "stress.jsonl").open("a") as f:
            f.write(json.dumps(res) + "\n")


if __name__ == "__main__":
    main()
