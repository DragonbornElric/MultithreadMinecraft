"""Real isolated Fabric client, driven over a loopback HTTP endpoint."""
import json
import os
import shutil
import signal
import socket
import subprocess
import time
import urllib.request
from pathlib import Path


class LabClient:
    def __init__(self, config, directory, variant="prototype"):
        self.process = self.display = None
        self.streams = []
        try:
            self._start(config, directory, variant)
        except Exception:
            self.close()
            raise

    def _start(self, config, directory, variant):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=False)
        self.process = self.display = None
        self.streams = []
        self.name = "DimensionLab"
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            self.port = s.getsockname()[1]
        (self.directory / "driver.port").write_text(str(self.port))
        (self.directory / "options.txt").write_text("onboardAccessibility:false\nrenderDistance:4\nsimulationDistance:4\nmaxFps:20\nvsync:false\npauseOnLostFocus:false\n")
        mods = self.directory / "mods"
        mods.mkdir()
        # The real current/baseline EndInv client mod, never a stub.
        shutil.copy2(config[variant + "_jars"][2], mods)
        manifest = json.loads(Path(config["client_manifest"]).read_text())
        fd_path = self.directory / "display-number"
        display_log = (self.directory / "xvfb.log").open("w")
        self.streams.append(display_log)
        with fd_path.open("w") as fd:
            self.display = subprocess.Popen([config["xvfb"], "-displayfd", str(fd.fileno()), "-screen", "0", "854x480x24", "-nolisten", "tcp", "-ac", "-fp", "built-ins"], pass_fds=(fd.fileno(),), stdout=display_log, stderr=subprocess.STDOUT, start_new_session=True)
        deadline = time.monotonic() + 15
        while not fd_path.read_text().strip():
            if self.display.poll() is not None or time.monotonic() > deadline:
                self.close()
                raise RuntimeError("Isolated Xvfb did not start")
            time.sleep(.1)
        env = os.environ.copy()
        env.update({"DISPLAY": ":" + fd_path.read_text().strip(), "LIBGL_ALWAYS_SOFTWARE": "1", "GALLIUM_DRIVER": "llvmpipe"})
        env.update(manifest["environment"])
        args = manifest["args"][:]
        for key, value in [("--gameDir", str(self.directory)), ("--username", self.name)]:
            if key in args:
                args[args.index(key) + 1] = value
            else:
                args += [key, value]
        args += ["--quickPlayMultiplayer", "127.0.0.1:25680"]
        jvm = [a for a in manifest["jvm"] if not a.startswith("-Xmx")]
        command = [config["java"], *jvm, "-Xmx2G", "-Dhttps.proxyHost=proxy", "-Dhttps.proxyPort=8080", "-Dhttp.proxyHost=proxy", "-Dhttp.proxyPort=8080", "-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts", "-cp", os.pathsep.join(manifest["classpath"]), manifest["main"], *args]
        (self.directory / "launch.json").write_text(json.dumps(command, indent=2) + "\n")
        log = (self.directory / "client.log").open("w")
        self.streams.append(log)
        self.process = subprocess.Popen(command, cwd=self.directory, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)

    def cmd(self, op, **values):
        request = {"op": op, **values}
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/cmd", data=json.dumps(request).encode(), headers={"Content-Type": "application/json"})
        # Never route loopback through the inherited HTTP proxy.
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=65) as response:
            out = json.load(response)
        with (self.directory / "actions.jsonl").open("a") as f:
            f.write(json.dumps({"request": request, "response": out}) + "\n")
        return out

    def ready(self, timeout=180):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if self.process.poll() is not None:
                return False
            try:
                if self.cmd("state").get("connected"):
                    return True
            except (OSError, ValueError):
                pass
            time.sleep(.5)
        return False

    def wait(self, op, predicate, timeout=15, **values):
        deadline = time.monotonic() + timeout
        while True:
            out = self.cmd(op, **values)
            if predicate(out):
                return out
            if time.monotonic() > deadline:
                raise AssertionError(f"Client {op} condition unmet: {out}")
            time.sleep(.2)

    def close(self):
        for p in [self.process, self.display]:
            if p is not None and p.poll() is None:
                os.killpg(p.pid, signal.SIGTERM)
                try:
                    p.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(p.pid, signal.SIGKILL)
                    p.wait(timeout=10)
        for stream in self.streams:
            stream.close()


def populated_endinv_fixture(server, config, directory, variant):
    """Player command + actual menu quick-move; no synthetic inventory service."""
    import re
    client = LabClient(config, directory, variant)
    try:
        if not client.ready():
            raise AssertionError("Real Fabric client did not join; inspect client.log")
        server.cmd("op " + client.name)
        server.cmd("effect give " + client.name + " minecraft:resistance infinite 255 true")
        start = client.cmd("messages")["count"]
        client.cmd("command", cmd="endinv new public")
        created = client.wait("messages", lambda r: any("Created a new public" in m["text"] for m in r["messages"]), since=start)
        message = next(m["text"] for m in created["messages"] if "Created a new public" in m["text"])
        uuid = re.search(r"[0-9a-f]{8}-[0-9a-f-]{27,}", message).group(0)
        index = None
        for n in range(16):
            if uuid in server.cmd(f"execute as {client.name} run endinv ofIndex {n}"):
                index = n
                break
        if index is None:
            raise AssertionError("Created inventory UUID could not be resolved")
        start = client.cmd("messages")["count"]
        client.cmd("command", cmd=f"endinv ofIndex {index} setDefault")
        client.wait("messages", lambda r: any("Set player's default" in m["text"] for m in r["messages"]), since=start)
        server.cmd("give " + client.name + " minecraft:diamond 64")
        client.wait("state", lambda r: sum(i["count"] for i in r.get("inventory", []) if i["id"] == "minecraft:diamond") == 64)
        client.cmd("command", cmd=f"endinv ofIndex {index} open")
        screen = client.wait("screen", lambda r: "EndlessInventory" in r.get("class", ""))
        slots = [s for s in screen["slots"] if s.get("player_inv") and s["id"] == "minecraft:diamond"]
        if sum(s["count"] for s in slots) != 64:
            raise AssertionError("Fixture diamond stack missing from real EndInv menu")
        for slot in slots:
            client.cmd("click", slot=slot["slot"], action="QUICK_MOVE")
        client.wait("state", lambda r: not any(i["id"] == "minecraft:diamond" for i in r.get("inventory", [])))
        client.cmd("close")
        observed = {"client": client.cmd("state"), "created": message, "selected_uuid": uuid, "selected_index": index, "deposited_item": "minecraft:diamond", "deposited_count": 64}
        if variant == "prototype":
            codec = server.cmd("endinv-cluster-snapshot")
            observed["codec"] = codec
            if "round-trip OK" not in codec or "stored_items=64;" not in codec or uuid not in codec:
                raise AssertionError("Populated real EndInv codec/count/selection mismatch: " + codec)
            observed["player_snapshot"] = server.cmd("mtmc player-snapshot " + client.name)
            if "Player diagnostic snapshot OK" not in observed["player_snapshot"]:
                raise AssertionError("Real player capture failed")
        return observed
    finally:
        client.close()
