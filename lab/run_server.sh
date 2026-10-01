#!/bin/bash
# Start the lab server with one mod variant, wait for "Done", print the PID.
#   lab/run_server.sh <variant> [lab_dir]
# variant: vanilla   no mods (Fabric loader only)
#          mtmc      MultithreadMC, parallelDimensions=true
#          mtmc-off  MultithreadMC loaded but parallelDimensions=false (overhead check)
#          async     the Async mod (AxalotLDev, parallel entity ticking) from $LAB_DIR/jars, for comparison
# Env: JAVA (default java on PATH; must be 25+), XMX (default 4G), MTMC_JAR (default the mod's build output).
set -euo pipefail
VARIANT="${1:?variant}"
LAB_DIR="${2:-${LAB_DIR:-/tmp/mtmc-lab}}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="${JAVA:-java}"
XMX="${XMX:-4G}"
MTMC_JAR="${MTMC_JAR:-$(ls "$REPO"/mod/build/libs/multithreadmc-*.jar 2>/dev/null | grep -v sources | head -1)}"
cd "$LAB_DIR/server"
rm -rf mods && mkdir -p mods config
case "$VARIANT" in
  vanilla) ;;
  mtmc|mtmc-off)
    cp "$MTMC_JAR" mods/
    PAR=true; [ "$VARIANT" = mtmc-off ] && PAR=false
    printf 'parallelDimensions=%s\ndeferCommandBlocks=true\nlogCrossLevelAccess=true\nstatsIntervalSeconds=10\n' "$PAR" > config/multithreadmc.properties ;;
  async)
    cp "$LAB_DIR"/jars/async-*.jar mods/ ;;
  *) echo "unknown variant $VARIANT" >&2; exit 2 ;;
esac
rm -f mtmc-stats.json
echo "$VARIANT" > variant.txt
nohup "$JAVA" -Xms"$XMX" -Xmx"$XMX" -jar fabric-server-launch.jar nogui > server.log 2>&1 &
PID=$!
echo $PID > server.pid
for _ in $(seq 1 600); do
  if grep -q 'Done (' server.log; then echo "$PID"; exit 0; fi
  if ! kill -0 $PID 2>/dev/null; then tail -40 server.log >&2; exit 1; fi
  sleep 1
done
echo "server did not finish starting" >&2; exit 1
