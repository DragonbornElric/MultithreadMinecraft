"""Evidence and freeze helpers. No test result is inferred from a skipped scenario."""
import hashlib
import json
import subprocess
from pathlib import Path

EXIT = {"PASS": 0, "FAIL": 1, "BLOCKED": 2, "NOT RUN": 3}


def sha(path):
    with open(path, "rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def limits():
    result = {}
    for file in ["cpu.max", "cpu.stat", "memory.max", "memory.current", "memory.events", "cpuset.cpus.effective"]:
        path = Path("/sys/fs/cgroup") / file
        result[file] = path.read_text().strip() if path.exists() else None
    result["host_scope"] = "single attached host; server and local probes share quota"
    result["remote_hosts"] = "not observed"
    return result


def manifest(config):
    result = {"config": config, "repositories": {}, "artifacts": {}}
    for name, directory in config["repositories"].items():
        args = ["git", "-C", directory]
        commit = subprocess.check_output(args + ["rev-parse", "HEAD"], text=True).strip()
        branch = subprocess.check_output(args + ["branch", "--show-current"], text=True).strip()
        paths = subprocess.check_output(args + ["ls-files", "--cached", "--others", "--exclude-standard", "-z"]).decode().split("\0")
        files = {p: sha(Path(directory) / p) for p in paths if p and (Path(directory) / p).is_file()}
        result["repositories"][name] = {"commit": commit, "branch": branch, "files": files}
    for path in config["artifacts"]:
        result["artifacts"][path] = sha(path) if Path(path).is_file() else None
    result["digest"] = hashlib.sha256(json.dumps(result, sort_keys=True).encode()).hexdigest()
    return result


def result(case, config, status, reason, observed=None, measurements=None, evidence=None):
    return {
        "schema": 1, "case": case["id"], "status": status, "exit_code": EXIT[status],
        "expected": case["expected"], "observed": observed, "reason": reason,
        "versions": config["versions"], "commit_ids": config["commits"],
        "limits": limits(), "configuration": config, "seed": config["seed"],
        "workload": case["scenario"], "measurements": measurements or {},
        "evidence": evidence or [], "scope": case.get("scope", "approved matrix"),
    }
