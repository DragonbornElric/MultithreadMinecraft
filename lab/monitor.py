"""Record a live server while players/bots come and go; optionally A/B the mod on the same load.

    python lab/monitor.py --host 127.0.0.1 --password mtmclab --label 9950x3d --minutes 60
    python lab/monitor.py --label 5950x --ab 180          # /mtmc on / off every 180 s

Only needs Python 3 and RCON (enable-rcon=true in server.properties), so it runs on Windows
too and can watch any server: no lab scripts, no bash.

Every --interval seconds (default 10) it appends one JSON line to --out with:

* players: total and per dimension, and the in-game time
* mspt: ``/tick query`` average and P50/P95/P99 over the last 100 ticks
* mtmc: MultithreadMC's last stats window (per-dimension ms, phase ms, overlap, threads,
  deferred and cross_level counters), if the mod is installed
* mode: ``on`` / ``off`` (parallel dimensions), so A/B samples can be split afterwards
* log: new "Exception", "Can't keep up" or ERROR lines, if --log points at the server log

With --ab N the parallel mode flips every N seconds (the mod applies it from the next tick).
The first 20 s after each flip are marked ``settling`` and left out of the summary.

At the end, or on Ctrl+C, it prints a summary grouped by mode and by player-count bucket,
and writes it to <out>.summary.json. Paste that into docs/LAB_RESULTS.md, or send it.
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import re
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from rcon import Rcon  # noqa: E402

DIMS = ["overworld", "the_nether", "the_end"]


def host_info(label: str) -> dict:
    cpu = platform.processor() or platform.machine()
    try:
        for line in open("/proc/cpuinfo"):
            if line.startswith("model name"):
                cpu = line.split(":", 1)[1].strip()
                break
    except OSError:
        pass
    return {"label": label, "cpu": cpu, "logical_cpus": os.cpu_count(), "os": platform.platform()}


def tick_query(r: Rcon) -> dict:
    txt = r.cmd("tick query")
    m = re.search(r"Average time per tick: ([\d.]+)ms", txt)
    out = {"avg": float(m.group(1)) if m else None}
    out.update({k: float(v) for k, v in re.findall(r"(P\d+): ([\d.]+)ms", txt)})
    return out


def players(r: Rcon) -> dict:
    per = {}
    for d in DIMS:
        txt = r.cmd(f"execute in minecraft:{d} if entity @a[x=0,y=0,z=0,distance=..100000000]")
        m = re.search(r"Count: (\d+)", txt)
        per[d] = int(m.group(1)) if m else 0
    return {"total": sum(per.values()), "per_dim": per}


def mtmc(r: Rcon) -> tuple[str | None, dict | None]:
    txt = r.cmd("mtmc status")
    if "Unknown" in txt or "Incorrect" in txt or "MultithreadMC" not in txt:
        return None, None
    mode = "off" if "off (vanilla)" in txt else "on"
    m = re.search(r"last stats: (\{.*\})", txt)
    try:
        return mode, json.loads(m.group(1)) if m else None
    except ValueError:
        return mode, None


class LogTail:
    PAT = re.compile(r"Exception|Can't keep up|/ERROR\]|deadlock|Watchdog", re.I)

    def __init__(self, path: str | None):
        self.path = Path(path) if path else None
        self.pos = self.path.stat().st_size if self.path and self.path.exists() else 0

    def new_lines(self) -> list[str]:
        if not self.path or not self.path.exists():
            return []
        size = self.path.stat().st_size
        if size < self.pos:  # rotated
            self.pos = 0
        with self.path.open("rb") as f:
            f.seek(self.pos)
            data = f.read()
        self.pos = size
        return [l for l in data.decode(errors="replace").splitlines()
                if self.PAT.search(l) and "Cross-dimension access" not in l][:20]


def bucket(n: int) -> str:
    return "0" if n == 0 else f"{(n - 1) // 5 * 5 + 1}-{(n - 1) // 5 * 5 + 5}"


def summarize(rows: list[dict]) -> dict:
    groups: dict[str, dict[str, list[dict]]] = {}
    for row in rows:
        if row.get("settling") or row["mspt"].get("avg") is None:
            continue
        groups.setdefault(row.get("mode") or "no-mod", {}).setdefault(bucket(row["players"]["total"]), []).append(row)
    out: dict = {}
    for mode, by in groups.items():
        out[mode] = {}
        for b, rs in sorted(by.items(), key=lambda kv: int(kv[0].split("-")[0])):
            avg = [x["mspt"]["avg"] for x in rs]
            p95 = [x["mspt"]["P95"] for x in rs if "P95" in x["mspt"]]
            ov = [x["mtmc"]["overlap"] for x in rs if x.get("mtmc") and "overlap" in x["mtmc"]]
            out[mode][f"players {b}"] = {
                "samples": len(rs),
                "mspt_avg": round(statistics.mean(avg), 2),
                "mspt_p95_mean": round(statistics.mean(p95), 2) if p95 else None,
                "mspt_worst_avg": max(avg),
                "overlap_mean": round(statistics.mean(ov), 2) if ov else None,
            }
    problems = sum(len(r.get("log", [])) for r in rows)
    out["log_problem_lines"] = problems
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25575)
    ap.add_argument("--password", default="mtmclab")
    ap.add_argument("--label", default=os.environ.get("LAB_HOST", ""), help="name for this machine, e.g. 9950x3d")
    ap.add_argument("--interval", type=float, default=10)
    ap.add_argument("--minutes", type=float, default=0, help="0 = until Ctrl+C")
    ap.add_argument("--ab", type=float, default=0, help="flip /mtmc on|off every N seconds (0 = don't touch it)")
    ap.add_argument("--log", default=None, help="server log to watch (e.g. logs/latest.log)")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    out = Path(args.out or f"monitor-{args.label or 'host'}-{time.strftime('%Y%m%d-%H%M%S')}.jsonl")
    r = Rcon(args.host, args.port, args.password)
    host = host_info(args.label)
    tail = LogTail(args.log)
    rows: list[dict] = []
    t0 = time.time()
    last_flip = t0
    mode, _ = mtmc(r)
    if args.ab and mode is None:
        sys.exit("--ab needs MultithreadMC on the server (/mtmc not found)")
    if args.ab:
        r.cmd("mtmc on")
    print(f"recording to {out}  host={host}")
    try:
        while not args.minutes or time.time() - t0 < args.minutes * 60:
            now = time.time()
            if args.ab and now - last_flip >= args.ab:
                cur, _ = mtmc(r)
                r.cmd("mtmc off" if cur == "on" else "mtmc on")
                last_flip = now
            mode, stats = mtmc(r)
            if mode == "off":
                stats = None  # the mod only updates its stats window while parallel is on
            row = {
                "t": round(now - t0, 1), "time": time.strftime("%Y-%m-%dT%H:%M:%S"), "host": host,
                "players": players(r), "mspt": tick_query(r), "mode": mode, "mtmc": stats,
                "settling": bool(args.ab) and now - last_flip < 20,
                "log": tail.new_lines(),
            }
            rows.append(row)
            with out.open("a") as f:
                f.write(json.dumps(row) + "\n")
            p = row["players"]
            print(f"{row['t']:7.0f}s  players {p['total']:2d} {p['per_dim']}  mode {mode}  "
                  f"mspt {row['mspt'].get('avg')} p95 {row['mspt'].get('P95')}"
                  + (f"  overlap {stats.get('overlap')}" if stats else "")
                  + (f"  LOG x{len(row['log'])}" if row["log"] else ""), flush=True)
            time.sleep(max(0.0, args.interval - (time.time() - now)))
    except KeyboardInterrupt:
        pass
    finally:
        if args.ab:
            r.cmd("mtmc on")
        summary = {"host": host, "samples": len(rows), "by_mode": summarize(rows)}
        Path(str(out) + ".summary.json").write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary, indent=2))
        r.close()


if __name__ == "__main__":
    main()
