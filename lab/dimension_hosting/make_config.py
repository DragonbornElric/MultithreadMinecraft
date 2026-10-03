#!/usr/bin/env python3
"""Generate a local manifest. Review/freeze it before running the matrix."""
import argparse
import json
import subprocess
from pathlib import Path


def jar(directory):
    jars = [p for p in Path(directory).glob("*.jar") if not any(s in p.name for s in ["-sources", "-javadoc", "-dev"]) ]
    if len(jars) != 1:
        raise RuntimeError(f"Expected one runtime JAR in {directory}: {jars}")
    return str(jars[0].resolve())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--approved-plan", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    root = args.workspace.resolve()
    plan = json.loads(args.approved_plan.read_text())
    repositories = {"mtmc": str(root / "mtmc"), "emma-smp": str(root / "emma-smp"), "endinv": str(root / "endinv"),
        "baseline-mtmc": str(root / "baseline-mtmc"), "baseline-smp": str(root / "baseline-smp"), "baseline-endinv": str(root / "baseline-endinv")}
    commits = {k: subprocess.check_output(["git", "-C", p, "rev-parse", "HEAD"], text=True).strip() for k, p in repositories.items()}
    prototype = [jar(root / "mtmc/mod/build/libs"), jar(root / "emma-smp/server/build/libs"), jar(root / "endinv/java/emma-endinv/fabric/build/libs")]
    baseline = [jar(root / "baseline-mtmc/mod/build/libs"), jar(root / "baseline-smp/server/build/libs"), jar(root / "baseline-endinv/java/emma-endinv/fabric/build/libs")]
    assets = json.loads((root / "assets/assets.json").read_text())
    stack = [a["path"] for a in assets if a["id"] != "launcher"]
    launcher = next(a["path"] for a in assets if a["id"] == "launcher")
    matrix = [{k: c[k] for k in ["id", "scenario", "expected"]} for c in plan["matrix"]]
    supplemental = [
        {"id": "CORE_LEDGER", "scope": "supplemental control plane; not real-mod transfer coverage", "scenario": "Durable transitions, invalid/stale/duplicate commands, competing requests, abrupt JVM death per phase and outbox persistence", "expected": "All executable ledger assertions pass, no skipped tests"},
        {"id": "CORE_TRANSPORT", "scope": "supplemental real loopback TLS/gRPC; not multi-PC coverage", "scenario": "mTLS peer identity/version/deadline checks, real network transfer/replay and async outbox client", "expected": "All executable TLS assertions pass, no skipped tests"},
        {"id": "SMP_UNIT", "scope": "supplemental existing emma-smp unit regression; not cross-server coverage", "scenario": "Existing server unit suite against changed source", "expected": "All existing unit assertions pass, no skipped tests"},
        {"id": "STACK_SMOKE", "scope": "supplemental real Fabric stack smoke; not active-player baseline or capacity", "scenario": "Real MTMC/emma-smp/EndInv1.4.5 boot, public inventory creation, actual EndInv codec roundtrip and MTMC cross-level selftest", "expected": "Actual required mods boot; commands succeed and selftest reports zero failures"},
    ]
    matrix = [matrix[0], *supplemental, *matrix[1:]]
    config = {
        "schema": 1, "repositories": repositories, "commits": commits,
        "java": "/workspace/.tools/jdk-25.0.4.1/bin/java", "gradle": "/workspace/.tools/gradle-9.7.1/bin/gradle", "gradle_home": "/workspace/.gradle",
        "prototype_jars": prototype, "baseline_jars": baseline, "stack_jars": stack, "launcher": launcher,
        "artifacts": [*prototype, *baseline, *stack, launcher, *map(str, sorted((root / "mtmc/coordination/build/install/mtmc-coordination/lib").glob("*.jar")))],
        "versions": {"minecraft": "26.2", "loader": "0.19.5", "fabric_api": "0.161.0+26.2", "java": "25.0.4.1", "endinv": "1.4.5", "grpc": "1.76.0", "protobuf": "4.33.0", "assets": assets},
        "seed": "mtmc-dimensions-approved-2026-10-03", "matrix": matrix, "approved_acceptance": plan["acceptance"],
        "remote_hosts": [], "remote_decision": "User explicitly requested multi-PC tests remain BLOCKED for this run",
        "gameplay_adapters_ready": False, "active_generator_ready": False, "permissions_stack_pinned": False,
        "owner_configs": str(root / "mtmc/lab/configs/owner-stack"),
        "synthetic_mobs_per_dimension": 96, "synthetic_settle_seconds": 5, "synthetic_samples": 6, "synthetic_sample_seconds": 2,
        "synthetic_scope": "Supplemental fixture only; zero active clients; not approved 30min playable-capacity workload",
        "steady_seconds": 1800, "warmup_seconds": 600, "plateaus": [28, 50, 75, 100, 150, 200, 250, 300],
        "server_heap": "3G; owner20GB heap cannot be replicated under16GiB quota", "view": 12, "simulation": 8, "gc": "ZGC",
    }
    args.out.write_text(json.dumps(config, indent=2) + "\n")
    print(args.out)


if __name__ == "__main__":
    main()
