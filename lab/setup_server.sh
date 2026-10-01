#!/bin/bash
# Prepare a Fabric 26.2 lab server for MultithreadMC benchmarks and stress tests.
#   lab/setup_server.sh [lab_dir]     (default: $LAB_DIR or /tmp/mtmc-lab)
# Creates <lab_dir>/server with fabric-server-launch.jar, offline mode, RCON on 25575
# (password mtmclab), a normal world (all three dimensions generate) and command blocks on.
# Which mod jars go in mods/ is decided per run by run_server.sh (variant vanilla|mtmc|...).
set -euo pipefail
LAB_DIR="${1:-${LAB_DIR:-/tmp/mtmc-lab}}"
MC_VERSION="${MC_VERSION:-26.2}"
LOADER="${FABRIC_LOADER:-0.19.5}"
INSTALLER="${FABRIC_INSTALLER:-1.1.2}"
SEED="${SEED:-mtmc-lab}"
mkdir -p "$LAB_DIR/server" "$LAB_DIR/jars"
cd "$LAB_DIR/server"
if [ ! -f fabric-server-launch.jar ]; then
  curl -sSfL -o fabric-server-launch.jar \
    "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION/$LOADER/$INSTALLER/server/jar"
fi
echo "eula=true" > eula.txt
cat > server.properties <<PROPS
online-mode=false
enforce-secure-profile=false
level-name=world
level-seed=$SEED
gamemode=survival
difficulty=normal
spawn-protection=0
view-distance=8
simulation-distance=8
max-players=8
enable-rcon=true
rcon.port=25575
rcon.password=mtmclab
enable-command-block=true
allow-flight=true
pause-when-empty-seconds=0
sync-chunk-writes=false
motd=MultithreadMC lab
PROPS
echo "server ready in $LAB_DIR/server (start it with lab/run_server.sh)"
