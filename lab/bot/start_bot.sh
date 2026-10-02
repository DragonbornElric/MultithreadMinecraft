#!/bin/bash
# Launch the Emma bridge bot (EmmaMinecraft261, branch mc-26.2) headless and join the lab server.
#   lab/bot/start_bot.sh            (returns once EmmaBot has joined; the client keeps running)
# The bot checkout is used as-is, never modified: a clone of the mc-26.2 branch in
# $EMMA_DIR (default $LAB_DIR/emma262, cloned on first use). Only build output and the
# Fabric run dir (options.txt, mods/) are written there.
# EndInv: the lab server has no EndInv server side, so the bot gets the empty stand-in from
# EmmaMinecraft261's tools/combat_lab/endinv_stub, compiled here against the 26.2 jar Loom
# downloaded for mod/ (build mod/ once first).
# Env: LAB_DIR (/tmp/mtmc-lab), EMMA_DIR, EMMA_REPO (git URL), SERVER (127.0.0.1:25565),
#      JAVA_HOME (a JDK 25), BOT_NAME (EmmaBot).
set -euo pipefail
LAB_DIR="${LAB_DIR:-/tmp/mtmc-lab}"
EMMA_DIR="${EMMA_DIR:-$LAB_DIR/emma262}"
EMMA_REPO="${EMMA_REPO:-https://github.com/DragonbornElric/EmmaMinecraft261.git}"
SERVER="${SERVER:-127.0.0.1:25565}"
BOT_NAME="${BOT_NAME:-EmmaBot}"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 25}"
mkdir -p "$LAB_DIR/bot"
[ -d "$EMMA_DIR/.git" ] || git clone -q --branch mc-26.2 --single-branch "$EMMA_REPO" "$EMMA_DIR"

STUB="$LAB_DIR/bot/endinv-lab-mod.jar"
if [ ! -f "$STUB" ]; then
  MC="$(ls ~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar)"
  CP="$MC:$(find ~/.gradle/caches/modules-2 -name '*.jar' | tr '\n' ':')"
  TMP="$(mktemp -d)"
  "$JAVA_HOME/bin/javac" -nowarn -proc:none -d "$TMP" -cp "$CP" $(find "$EMMA_DIR/tools/combat_lab/endinv_stub/src" -name '*.java')
  cp "$EMMA_DIR/tools/combat_lab/endinv_stub/fabric.mod.json" "$TMP/"
  (cd "$TMP" && "$JAVA_HOME/bin/jar" cf "$STUB" .)
fi

RUN_DIR="$EMMA_DIR/java/emma-pathfinder/fabric/run"
mkdir -p "$RUN_DIR/mods"
rm -f "$RUN_DIR"/mods/endinv-lab-mod.jar "$RUN_DIR"/mods/emma_endinv-*.jar
cp "$STUB" "$RUN_DIR/mods/"
cat > "$RUN_DIR/options.txt" <<'OPTS'
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
pauseOnLostFocus:false
renderDistance:6
simulationDistance:6
maxFps:20
graphicsMode:0
renderClouds:"false"
particles:2
soundCategory_master:0.0
tutorialStep:none
OPTS

MARK=$(grep -c "$BOT_NAME joined the game" "$LAB_DIR/server/server.log" 2>/dev/null || true); MARK=${MARK:-0}
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
# Redirect the whole background subshell: a subshell left holding the caller's stdout keeps a
# capturing caller (subprocess.run(capture_output=True)) waiting forever.
(cd "$EMMA_DIR/java/emma-pathfinder" && exec setsid nohup xvfb-run -n 99 -f "$LAB_DIR/bot/Xauthority" -s "-screen 0 1280x720x24" \
  sh ./gradlew -Dorg.gradle.java.home="$JAVA_HOME" -Dorg.gradle.java.installations.paths="$JAVA_HOME" \
  -PemmaEndinvJar="$STUB" --console=plain :fabric:runClient \
  --args="--quickPlayMultiplayer $SERVER --username $BOT_NAME") > "$LAB_DIR/bot/client.log" 2>&1 < /dev/null &
for _ in $(seq 1 300); do
  sleep 3
  if grep -qE 'BUILD FAILED|Game crashed' "$LAB_DIR/bot/client.log"; then
    grep -E 'error:|BUILD FAILED|Game crashed' "$LAB_DIR/bot/client.log" | head -20; exit 1
  fi
  NOW=$(grep -c "$BOT_NAME joined the game" "$LAB_DIR/server/server.log" 2>/dev/null || true); NOW=${NOW:-0}
  if [ "$NOW" -gt "$MARK" ]; then echo "$BOT_NAME joined"; exit 0; fi
done
echo "timed out waiting for $BOT_NAME to join" >&2; exit 1
