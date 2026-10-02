#!/bin/bash
# Download the newest Fabric 26.2 build of other mods into $LAB_DIR/jars/all/<slug>.jar, for
# run_server.sh's +name variants (e.g. mtmc+lithium+servercore).
#   lab/fetch_mods.sh [slug ...]   (default: fabric-api lithium servercore moonrise-opt)
# Moonrise is fetched only to check that the loader refuses it next to MultithreadMC.
set -euo pipefail
LAB_DIR="${LAB_DIR:-/tmp/mtmc-lab}"
MC="${MC_VERSION:-26.2}"
mkdir -p "$LAB_DIR/jars/all"
[ $# -gt 0 ] || set -- fabric-api lithium servercore moonrise-opt
for slug in "$@"; do
  python3 - "$slug" "$MC" "$LAB_DIR/jars/all" <<'PY'
import json, sys, urllib.parse, urllib.request
slug, mc, out = sys.argv[1:]
q = urllib.parse.urlencode({"loaders": '["fabric"]', "game_versions": json.dumps([mc])})
versions = json.load(urllib.request.urlopen(f"https://api.modrinth.com/v2/project/{slug}/version?{q}"))
if not versions:
    sys.exit(f"{slug}: no Fabric {mc} build on Modrinth")
v = versions[0]
f = next(x for x in v["files"] if x["primary"])
urllib.request.urlretrieve(f["url"], f"{out}/{slug}.jar")
print(f"{slug} {v['version_number']} ({v['date_published'][:10]})")
PY
done
