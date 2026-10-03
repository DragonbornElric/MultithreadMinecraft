import argparse
import importlib.util
import json
import os
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from common import EXIT, result
from server import LabServer, synthetic_fixture
from client import populated_endinv_fixture


def gradle(config, cwd, arguments, directory):
    log = directory / "gradle.log"
    env = os.environ.copy()
    env["JAVA_HOME"] = str(Path(config["java"]).parents[1])
    env["PATH"] = str(Path(config["java"]).parent) + os.pathsep + env["PATH"]
    env["GRADLE_USER_HOME"] = config["gradle_home"]
    command = ["bash", config["gradle"], "--offline", "--no-daemon", "--max-workers=2", "-PmtmcEvidenceDir=" + str(directory / "junit"), *arguments]
    with log.open("w") as stream:
        p = subprocess.run(command, cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=300)
    xmls = sorted((directory / "junit").glob("TEST-*.xml"))
    counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for path in xmls:
        root = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(root.attrib.get(key, 0))
    return p.returncode, counts, [str(log), *map(str, xmls)]


def execute(case, config, directory):
    key = case["id"]
    if key == "TWO_NODE_COMM":
        from two_node import execute_two_node
        return execute_two_node(case, config, directory)
    if key == "ENV":
        checks = {
            "runtime_artifacts_present": all(Path(p).is_file() for p in config["artifacts"]),
            "java25": "25." in subprocess.check_output([config["java"], "-version"], stderr=subprocess.STDOUT, text=True),
            "openssl": bool(shutil.which("openssl")),
            "xvfb": Path(config["xvfb"]).is_file(),
            "real_client_manifest": Path(config["client_manifest"]).is_file(),
            "websocket_client": importlib.util.find_spec("websocket") is not None,
            "remote_hosts_attached": bool(config["remote_hosts"]),
            "gameplay_adapters_ready": config["gameplay_adapters_ready"],
            "active_generator_ready": config["active_generator_ready"],
            "permissions_stack_pinned": config["permissions_stack_pinned"],
        }
        return result(case, config, "PASS" if all(checks.values()) else "BLOCKED", "Complete matrix prerequisites" if all(checks.values()) else "Missing full-matrix prerequisites; independent scenarios still execute", checks)
    if key in {"CORE_LEDGER", "CORE_TRANSPORT", "SMP_UNIT"}:
        if key == "CORE_LEDGER":
            cwd = config["repositories"]["mtmc"] + "/coordination"
            args = ["test", "--tests", "dev.mtmc.coordination.TransferLedgerTest", "--tests", "dev.mtmc.coordination.DurableOutboxTest", "--tests", "dev.mtmc.coordination.SessionLedgerTest"]
        elif key == "CORE_TRANSPORT":
            cwd = config["repositories"]["mtmc"] + "/coordination"
            args = ["test", "--tests", "dev.mtmc.coordination.TransportTest"]
        else:
            cwd = config["repositories"]["emma-smp"]
            args = [":server:test"]
        code, counts, evidence = gradle(config, cwd, args, directory)
        status = "PASS" if code == 0 and counts["tests"] > 0 and not (counts["failures"] or counts["errors"] or counts["skipped"]) else "FAIL"
        return result(case, config, status, "Executable checks; no simulated Minecraft coverage", {"command_exit": code, "junit": counts}, evidence=evidence)
    if key == "BOOT":
        observations = []
        evidence = []
        for worker in ["a", "b"]:
            server = LabServer(config, directory / worker, cluster=True)
            try:
                ready = server.ready()
                text = server.log_path.read_text(errors="replace")
                observations.append({"worker": worker, "ready": ready, "explicit_integration_gate": "cluster gameplay BLOCKED" in text or "gameplay BLOCKED" in text})
            finally:
                server.close()
                evidence.append(str(server.log_path))
        # Guards passing is not distributed boot passing. It exposes the missing implementation.
        return result(case, config, "FAIL", "Distributed gameplay acceptance not met", observations, evidence=evidence)
    if key in {"STACK_SMOKE", "BASE0", "BASE1"}:
        baseline = key.startswith("BASE")
        server = LabServer(config, directory / "server", variant="baseline" if baseline else "prototype", regions=key == "BASE1")
        try:
            ready = server.ready()
            if not ready:
                return result(case, config, "FAIL", "Real mod stack did not reach ready", {"ready": False}, evidence=[str(server.log_path)])
            version = server.cmd("emmasmp version")
            fixture = populated_endinv_fixture(server, config, directory / "client", "baseline" if baseline else "prototype")
            create = fixture["created"]
            metrics = synthetic_fixture(server, config)
            measurements = directory / "measurements.json"
            measurements.write_text(json.dumps(metrics, indent=2) + "\n")
            observed = {"ready": True, "emma_smp": version, "endinv_created": create, "real_client_fixture": fixture}
            if not baseline:
                observed["endinv_codec"] = server.cmd("endinv-cluster-snapshot")
                observed["cluster_status"] = server.cmd("mtmc cluster")
                ok = "emma-smp" in version and "Created a new public" in create and "round-trip OK" in observed["endinv_codec"] and "0 failed" in metrics["selftest"]
                status, reason = ("PASS", "Real-stack boot/codec/selftest only") if ok else ("FAIL", "Real-stack smoke outcome mismatch")
            else:
                status, reason = "BLOCKED", "Synthetic off/on measurements available; approved active-player/conservation baseline needs the pinned workload and permission stack; one fixture client is insufficient"
            return result(case, config, status, reason, observed, metrics, [str(server.log_path), str(server.directory / "commands.jsonl"), str(measurements), str(directory / "client" / "client.log"), str(directory / "client" / "actions.jsonl")])
        finally:
            server.close()
    if key in {"LOADPC", "LIFECYCLE"}:
        return result(case, config, "BLOCKED", "No separate host/generator; distributed gameplay adapters incomplete", {"remote_hosts": config["remote_hosts"], "active_generator_ready": config["active_generator_ready"]})
    return result(case, config, "BLOCKED", "Depends on distributed gameplay boot and asynchronous real-mod mutation/admission adapters", {"blocking_case": "BOOT", "gameplay_adapters_ready": False})


def main(case_id):
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    case = next(c for c in config["matrix"] if c["id"] == case_id)
    args.out.mkdir(parents=True, exist_ok=False)
    start = time.time()
    try:
        record = execute(case, config, args.out)
    except Exception as error:
        record = result(case, config, "FAIL", "Scenario execution error; inspect evidence", {"exception": repr(error)}, evidence=[str(args.out)])
    record["started_unix"] = start
    record["duration_seconds"] = time.time() - start
    (args.out / "result.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({"case": case_id, "status": record["status"], "result": str(args.out / "result.json")}))
    raise SystemExit(EXIT[record["status"]])
