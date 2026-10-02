#!/bin/bash
# Start the lab server with one mod variant, wait for "Done", print the PID.
#   lab/run_server.sh <variant> [lab_dir]
# variant: vanilla   no mods (Fabric loader only)
#          mtmc      MultithreadMC, parallelDimensions=true; mtmc@N = N worker threads (default 0 = one per dimension)
#          mtmc-off  MultithreadMC loaded but parallelDimensions=false (overhead check)
#          async     the Async mod (AxalotLDev, parallel entity ticking) from $LAB_DIR/jars, for comparison
#          +name     any variant can add other mods: vanilla+lithium+servercore, mtmc@3+lithium.
#                    Each +name copies $LAB_DIR/jars/all/<name>*.jar (lab/fetch_mods.sh downloads them);
#                    fabric-api comes along whenever another mod is added.
# Env: JAVA (default java on PATH; must be 25+), XMX (default 4G), MTMC_JAR (default the mod's build output),
#      JVM_FLAGS (extra JVM flags, e.g. "-XX:+UseZGC"), VIEW / SIM (view / simulation distance; default 8 / 8),
#      MTMC_PROPS (space-separated key=value lines appended to the mod's config),
#      CONFIGS (a directory copied over config/ after the mod's own file, e.g. lab/configs/owner-stack).
set -euo pipefail
VARIANT="${1:?variant}"
EXTRA=""
case "$VARIANT" in *+*) EXTRA="${VARIANT#*+}"; VARIANT="${VARIANT%%+*}" ;; esac
THREADS=0
case "$VARIANT" in *@*) THREADS="${VARIANT#*@}"; VARIANT="${VARIANT%@*}" ;; esac
LAB_DIR="${2:-${LAB_DIR:-/tmp/mtmc-lab}}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
if [ -n "${CONFIGS:-}" ]; then CONFIGS="$(cd "$CONFIGS" && pwd)"; fi
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
    printf 'parallelDimensions=%s\nthreads=%s\ndeferCommandBlocks=true\nlogCrossLevelAccess=true\nstatsIntervalSeconds=10\n' "$PAR" "$THREADS" > config/multithreadmc.properties
    # MTMC_PROPS: extra lines for the mod's config, e.g. "lagCaps=true capItems=50"
    for kv in ${MTMC_PROPS:-}; do echo "$kv" >> config/multithreadmc.properties; done ;;
  async)
    cp "$LAB_DIR"/jars/async-*.jar mods/ ;;
  *) echo "unknown variant $VARIANT" >&2; exit 2 ;;
esac
if [ -n "$EXTRA" ]; then
  for m in fabric-api ${EXTRA//+/ }; do
    J="$(ls "$LAB_DIR"/jars/all/"$m"*.jar 2>/dev/null | head -1)"
    [ -n "$J" ] || { echo "no jar for $m in $LAB_DIR/jars/all (run lab/fetch_mods.sh)" >&2; exit 2; }
    cp "$J" mods/
  done
fi
if [ -n "${CONFIGS:-}" ]; then cp -r "$CONFIGS"/. config/; rm -f config/README.md; fi
sed -i "s/^view-distance=.*/view-distance=${VIEW:-8}/; s/^simulation-distance=.*/simulation-distance=${SIM:-8}/" server.properties
rm -f mtmc-stats.json
echo "$1" > variant.txt
nohup "$JAVA" -Xms"$XMX" -Xmx"$XMX" ${JVM_FLAGS:-} -jar fabric-server-launch.jar nogui > server.log 2>&1 &
PID=$!
echo $PID > server.pid
for _ in $(seq 1 600); do
  if grep -q 'Done (' server.log; then echo "$PID"; exit 0; fi
  if ! kill -0 $PID 2>/dev/null; then tail -40 server.log >&2; exit 1; fi
  sleep 1
done
echo "server did not finish starting" >&2; exit 1
