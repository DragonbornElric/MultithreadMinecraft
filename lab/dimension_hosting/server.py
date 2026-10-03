"""Disposable loopback-only servers. This never attaches to an existing process."""
import json
import os
import shutil
import signal
import socket
import subprocess
import time
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from rcon import Rcon


class LabServer:
    def __init__(self, config, directory, variant="prototype", regions=False, cluster=False, ports=(25680, 25681), lab_peer=None):
        self.config, self.directory = config, Path(directory)
        if self.directory.exists():
            raise RuntimeError("Disposable server directory already exists; refusing to overwrite evidence")
        self.mc_port, self.rcon_port = ports
        for port in ports:
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", port))
        self.directory.mkdir(parents=True)
        mods = self.directory / "mods"
        mods.mkdir()
        shutil.copy2(config["launcher"], self.directory / "fabric-server-launch.jar")
        for jar in config[variant + "_jars"] + config["stack_jars"] + ([config["peer_lab_jar"]] if lab_peer else []):
            shutil.copy2(jar, mods / Path(jar).name)
        (self.directory / "eula.txt").write_text("eula=true\n")
        # These are isolated lab fixtures, not production identities or credentials.
        self.password = "disposable-mtmc-lab"
        (self.directory / "server.properties").write_text("\n".join([
            "server-ip=127.0.0.1", "server-port=" + str(self.mc_port), "online-mode=false", "enforce-secure-profile=false",
            "enable-rcon=true", "rcon.port=" + str(self.rcon_port), "rcon.password=" + self.password,
            "level-name=world", "level-seed=" + str(config["seed"]), "max-players=" + ("0" if lab_peer else "300"), "spawn-protection=0",
            "view-distance=12", "simulation-distance=8", "gamemode=survival", "difficulty=normal",
            "pause-when-empty-seconds=0", "enable-command-block=true", "allow-flight=true", "sync-chunk-writes=true", "motd=DISPOSABLE MTMC LAB"
        ]) + "\n")
        configs = self.directory / "config"
        shutil.copytree(config["owner_configs"], configs)
        (configs / "multithreadmc.properties").write_text("\n".join([
            "parallelDimensions=true", "threads=3", "regions=" + str(regions).lower(),
            "regionThreads=2", "regionCellChunks=4", "regionMinEntities=64", "sensorPhase=false",
            "lagThrottle=false", "lagCaps=false", "statsIntervalSeconds=5"
        ]) + "\n")
        self.log_path = self.directory / "server.log"
        self.log = self.log_path.open("w")
        command = [config["java"], "-Xms512M", "-Xmx3G", "-XX:+UseZGC", "-Xlog:gc*:file=gc.log:time,uptime,level,tags",
            "-Dhttps.proxyHost=proxy", "-Dhttps.proxyPort=8080", "-Dhttp.proxyHost=proxy", "-Dhttp.proxyPort=8080",
            "-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"]
        if lab_peer:
            command.append("-Dmtmc.lab.peer.enabled=true")
            command.extend("-Dmtmc.lab." + str(key) + "=" + str(value) for key, value in lab_peer.items())
        if cluster:
            command.append("-Dmtmc.cluster.enabled=true")
        command += ["-jar", "fabric-server-launch.jar", "nogui"]
        (self.directory / "launch.json").write_text(json.dumps(command, indent=2) + "\n")
        self.process = subprocess.Popen(command, cwd=self.directory, stdout=self.log, stderr=subprocess.STDOUT, start_new_session=True)
        self.rcon = None

    def ready(self, timeout=180):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            text = self.log_path.read_text(errors="replace")
            if self.process.poll() is not None:
                return False
            if "Done (" in text:
                self.rcon = Rcon("127.0.0.1", self.rcon_port, self.password)
                return True
            time.sleep(0.5)
        return False

    def cmd(self, command):
        output = self.rcon.cmd(command)
        with (self.directory / "commands.jsonl").open("a") as f:
            f.write(json.dumps({"command": command, "response": output}) + "\n")
        return output

    def close(self):
        if self.rcon is not None and self.process.poll() is None:
            try:
                self.rcon.cmd("stop")
            except (OSError, EOFError):
                pass
            self.rcon.close()
        if self.process.poll() is None:
            try:
                self.process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGTERM)
                try:
                    self.process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(self.process.pid, signal.SIGKILL)
                    self.process.wait(timeout=10)
        self.log.close()


def synthetic_fixture(server, config):
    """Supplemental mob workload only; never called active-player capacity."""
    server.cmd("gamerule spawn_mobs false")
    for dimension in ["minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"]:
        prefix = "execute in " + dimension + " run "
        server.cmd(prefix + "forceload add 0 0 15 15")
        server.cmd(prefix + "fill 0 199 0 15 199 15 minecraft:glass")
        for i in range(config["synthetic_mobs_per_dimension"]):
            server.cmd(prefix + f'summon minecraft:cow {2+i%12} 200 {2+(i//12)%12} {{PersistenceRequired:1b}}')
    server.cmd("mtmc selftest")
    time.sleep(config["synthetic_settle_seconds"])
    selftest = server.cmd("mtmc selftest result")
    samples = []
    for _ in range(config["synthetic_samples"]):
        samples.append({"tick_query": server.cmd("tick query"), "mtmc": server.cmd("mtmc status")})
        time.sleep(config["synthetic_sample_seconds"])
    return {"label": "synthetic mob smoke, zero active clients; not playable capacity", "selftest": selftest, "samples": samples}
