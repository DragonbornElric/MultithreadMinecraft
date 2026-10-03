#!/usr/bin/env python3
"""Freeze first, finish independent cases, then aggregate. Never fixes implementation."""
import argparse
import collections
import json
import subprocess
import sys
from pathlib import Path
from common import manifest, result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    args.out.mkdir(parents=True, exist_ok=False)
    frozen = manifest(config)
    frozen["config_file_sha256"] = __import__("common").sha(args.config)
    (args.out / "freeze.json").write_text(json.dumps(frozen, indent=2) + "\n")
    records = []
    stop = None
    for case in config["matrix"]:
        directory = args.out / case["id"]
        current = manifest(config)
        if current["digest"] != frozen["digest"] or __import__("common").sha(args.config) != frozen["config_file_sha256"]:
            stop = "Frozen source/artifact/config changed; suite blocked"
        if stop:
            directory.mkdir()
            record = result(case, config, "NOT RUN", stop)
            (directory / "result.json").write_text(json.dumps(record, indent=2) + "\n")
        else:
            script = Path(__file__).parent / "tests" / ("t_" + case["id"].lower() + ".py")
            with (args.out / (case["id"] + ".log")).open("w") as log:
                try:
                    process = subprocess.run([sys.executable, str(script), "--config", str(args.config.resolve()), "--out", str(directory.resolve())], stdout=log, stderr=subprocess.STDOUT, timeout=600)
                    record = json.loads((directory / "result.json").read_text())
                    if process.returncode != record["exit_code"]:
                        raise RuntimeError("Script exit and result disagree")
                except Exception as e:
                    directory.mkdir(exist_ok=True)
                    record = result(case, config, "FAIL", "Harness execution/result contract failure", repr(e), evidence=[str(args.out / (case["id"] + ".log"))])
                    (directory / "result.json").write_text(json.dumps(record, indent=2) + "\n")
        records.append(record)
        with (args.out / "results.jsonl").open("a") as f:
            f.write(json.dumps(record) + "\n")
        print(case["id"] + ": " + record["status"], flush=True)
    final = manifest(config)
    summary = {"counts": dict(collections.Counter(r["status"] for r in records)), "total": len(records),
        "frozen_digest": frozen["digest"], "final_digest": final["digest"],
        "freeze_unchanged": final["digest"] == frozen["digest"],
        "all_approved_cases_accounted_for": all(c["id"] in {r["case"] for r in records} for c in config["matrix"]),
        "capacity_claim": "NONE; no active-player multi-PC capacity was measured", "records": [str(args.out / r["case"] / "result.json") for r in records]}
    (args.out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary["counts"]), flush=True)
    raise SystemExit(1 if summary["counts"].get("FAIL") else 2 if any(summary["counts"].get(s) for s in ["BLOCKED", "NOT RUN"]) else 0)


if __name__ == "__main__":
    main()
