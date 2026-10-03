# Dimension hosting prototype: control plane and explicit gameplay gate

This development branch contains a durable control-plane prototype and a repeatable
runner for the approved matrix. **Distributed Minecraft gameplay is not implemented.**
Passing ledger/transport tests must not be reported as player transfer, EndInv concurrency,
multi-machine performance, or capacity coverage. No production deployment is appropriate.

## Implemented

`coordination/` builds separately with Java25. It uses generated protobuf messages,
gRPC1.76.0 with shaded Netty, mandatory mTLS and URI SAN identities
`spiffe://mtmc/<cluster>/<node>`. Peer roles are checked for each transition, not trusted
from request fields. A deadline is required. The disk writer queue and in-flight
outbox are bounded. Peer traffic uses its own port; the module does not accept
Minecraft connections.

SQLite WAL with synchronous FULL commits player ownership/epoch, transfer phase,
snapshot/digest and operation receipt together before acknowledging. Original operation
IDs replay their original results; conflicting payloads or peers are rejected. Snapshot
recovery is restricted to participants. A pending transfer cannot be replaced by a second
one. Client reconnect admission cannot overwrite an existing owner or thaw a frozen player.

The asynchronous peer client persists its command before send, retries transient failures
with the same operation ID, and recovers pending commands on restart. Unknown/permanent
errors retain pending data for reconciliation; they do not manufacture success. Callers
must apply completions on their Minecraft owner queue and must never join a result on a tick.

The state machine is REQUESTED -> QUIESCED -> PREPARED -> COMMITTED -> ACTIVE -> CLEANED.
Only the source can abort before COMMITTED. No heartbeat timeout reassigns ownership.
The control plane trusts authenticated peers to report actual quiescence/preparation;
the absent gameplay adapters are therefore a hard startup gate, not optional safety.

The real mod changes expose detached emma-smp balance/teleport state and an EndInv codec
snapshot/roundtrip command. EndInv's global snapshot is for authority seeding/reconciliation,
**not** something copied per player. Original local behavior remains the default. Setting
`-Dmtmc.cluster.enabled=true` fails startup until the integration is implemented.

## Still required

* Own only assigned gameplay dimensions, including vanilla Overworld/global bootstrap changes.
* Authenticated client admission/redirect, session fencing and native26.2 transfer compatibility.
* Source quiescence, complete player/entity/passenger capture, application journal/tombstones
  and restart reconciliation for local world effects.
* Async real EndInv mutations and escrow coupled to player/menu/item/entity state; one station clock.
* Async emma-smp global transactions, schedules, cross-node player lookup and every teleport path.
* Adapt synchronous API consumers explicitly; stale caches or network waits on tick threads are invalid.
* Full client fixtures, permissions stack, raw tick/main-thread metrics and remote host resources.

## Evidence runner

First prepare the real source builds and pinned assets. `prepare_assets.py` requires exact
Fabric26.2 versions and verifies Modrinth SHA512. `make_config.py` records source commits,
artifact paths, the approved matrix and supplemental checks. Review that config, then run:

```sh
python lab/dimension_hosting/run_suite.py --config /absolute/matrix-config.json --out /absolute/new-run
```

Each scenario has its own `tests/t_*.py` entrypoint and writes JSON with status, exit code,
expected/observed outcomes, versions/commits, limits, configuration/seed/workload, measurements
and evidence paths. Exit codes are0 PASS,1 FAIL,2 BLOCKED,3 NOT RUN. The runner freezes
source/artifact/config hashes, continues safe independent cases after failures, and emits
all scheduled records. A freeze violation stops the remaining scenarios as NOT RUN.
No suite run patches implementation or changes thresholds.

The existing single-server baseline is built in detached worktrees at the original commits.
Both regions settings use fresh seeded worlds and the same supplemental mob fixture. Without
real clients, these baseline cases stay BLOCKED even when synthetic samples exist. The
STACK_SMOKE check separately verifies actual mods and EndInv codec behavior. It does not
replace the approved baseline. CORE_* and SMP_UNIT are supplemental evidence only.

The local lab binds Minecraft/RCON to loopback on25680/25681, refuses existing directories
and occupied ports, and stops only its own processes. No production worlds/accounts/services
or combat lab directories are used. Multi-PC tests remain BLOCKED by user decision for this run.

After the whole frozen run, group failures and blockers. Seek approval before any fixes,
then rerun targeted checks and the complete matrix. No main/master/default branch push,
merge, or deployment is authorized.

## Second local phase

The lab now provisions a genuine Fabric 26.2 client with the real EndInv client mod,
using an isolated Xvfb display and a direct, frozen launch manifest. The smoke fixture
creates a public inventory through a player command, selects it, deposits 64 diamonds
through the real menu quick-move packet, then checks the actual global codec and
selection. This is one fixture client; baseline capacity still requires the approved
workload, permission stack, warmup, duration, and plateau checks.

`/mtmc player-snapshot <player>` captures detached vanilla player NBT, the carried
cursor and live menu slots on the server thread. `/endinv-cluster-snapshot` reports
stored counts and the actual global persisted schema. These diagnostic commands are
admin-only and do not freeze players, write peer snapshots, restore state, or authorize
admission. A live menu must be settled before a real handoff. Their NBT output is intended
for disposable test worlds and contains player state.

The authority ledger is now bound to its cluster, lobby, and sorted peer allowlist.
Reopening with different configuration fails. Unbound legacy prototype ledgers require
an explicit offline migration; this phase uses fresh disposable databases. Node boot
sessions and enforcement of current authority epochs by gameplay remain unfinished.

Client prerequisites for this environment: Debian `xvfb_21.1.16-1.3+deb13u4_amd64.deb`
from `https://deb.debian.org/debian/pool/main/x/xorg-server/`, SHA256
`893d87bf159b6de077a929fc796a73c39f837d45310b2076e3eeebebb80552b6`,
extracted without root into `/workspace/mtmc-prototype/tools/xvfb`. Existing Mesa/X11
libraries provide software rendering. The asset preparation script checks every object
against the Mojang 26.2-32 index SHA1 and size. Prepare `exportLabLaunch` in
`emma-smp/tools/lab/driver` with the pinned Java/Gradle/proxy configuration, then generate
the matrix config. The freeze includes client classes/resources, runtime classpath,
launch configuration, native libraries, all game assets, and the Xvfb package/binary.

## Durable boot sessions and admission checks (protocol version 2)

The authority persists each authenticated node's boot UUID and monotonically increasing
generation in the same SQLite ledger, with a history preventing retired boots from
being registered again. Starting a new boot requires the previous generation to match;
delayed registration messages cannot replace a newer generation. Replaying the exact
current registration after ACK loss returns the same durable session. Mutating admission
and transfer commands must carry the current boot UUID/generation, even when replaying
an existing operation receipt. Version 1 messages are rejected.

`SessionClient` provides bounded asynchronous registration and current-owner admission
checks over the dedicated mTLS peer channel. Construction and close happen off the game
thread. A runtime-directory file lock prevents two clients using that directory, and each
new client instance creates a fresh boot UUID. Authority admission checks require the
current node session, exact player epoch, ownership by the authenticated caller, and an
unfrozen owner. These are point-in-time diagnostic decisions, **not** admission leases or
a Minecraft login/restore adapter. Another session/ownership change can occur after a
response; tick/mutation fencing and a durable application journal remain necessary.

`PeerClient` reconciles a local/cached or newly acknowledged receipt against current
session, owner, and transfer phase before returning it. The reads do not form an atomic
application lease and must not authorize gameplay alone. Stale commands and durable
receipts remain available for reconciliation; a new boot must inspect authority state
and use a new operation ID for any new command, preserving old receipt identity. A
restart does not automatically resume a stale command as if it were current.

The frozen regression adds real SQLite restart/abrupt-process-death session checks,
competing boot registrations, retired mutation/receipt rejection, and real mTLS async
admission tests. Opaque ledger fixtures still do not count as player-state transfer tests.
Minecraft cluster startup guards remain enabled because worker dimension isolation,
source quiescence, destination restoration and global EndInv/emma-smp mutation adapters
are unfinished.

## Review and local testing

The feature branch is `feature/dimension-hosting-prototype` in all three changed repositories:
`DragonbornElric/MultithreadMinecraft`, `DragonbornElric/emma-smp`, and
`DragonbornElric/Emma-EndInv`. Use the three linked draft PRs together for the full local stack.
The coordinator unit tests can run independently. emma-smp's ordinary unit tests remain local.
The EndInv codec probe and populated smoke fixture require the real EndInv source build; the
cached 1.4.4 jar is not a substitute for the tested 1.4.5 source.

For control-plane and server unit checks on Linux/WSL, install Java 25, Gradle 9.7.1 and OpenSSL,
set `JAVA_HOME` to that Java installation, and run from the indicated checkout:

```bash
# MultithreadMinecraft
 gradle -p coordination test installDist
 gradle -p mod assemble
# emma-smp
 gradle :server:test :server:assemble
# Emma-EndInv/java/emma-endinv (Fabric build only)
 gradle -Dorg.gradle.java.home="$JAVA_HOME" -Pfolia_version= :fabric:assemble
```

Use fresh disposable worlds, not production data. Cluster gameplay deliberately refuses
`-Dmtmc.cluster.enabled=true`; that is an unfinished acceptance case, not a launch instruction
for a safe distributed server. `/mtmc cluster`, `/mtmc player-snapshot <player>` and
`/endinv-cluster-snapshot` expose admin-only diagnostic behavior on the normal local stack.
The coordinator can be launched separately with a fresh database and lab mTLS certificates;
its usage and peer identity requirements are documented above. Unit tests create their own
PKI and abrupt-death subprocesses in temporary directories.

The full matrix currently assumes the Linux `/workspace` tool/cache layout of this run and
uses isolated Xvfb/llvmpipe clients plus loopback ports 25680/25681. It is not a turnkey native
Windows benchmark. Prepare source/baseline worktrees and exact pinned assets, export the
client manifest using `exportLabLaunch` in `emma-smp/tools/lab/driver`, then generate a **new**
config with `make_config.py`. Review/override Java, Gradle, Gradle cache, Xvfb and client launch
paths for the local environment; some preparation scripts still assume `/workspace`. The
exported client classpath/native paths must be regenerated locally. Never use the archived
cloud freeze/config as a local launch manifest. Run the suite into a new output directory:

```bash
python lab/dimension_hosting/run_suite.py --config /absolute/new-config.json --out /absolute/new-run
```

The original baseline revisions are MTMC `7a4d74762750c1d43410eb86083794582e5498c8`,
emma-smp `afa8cc96e01d1cd78a321120bab7ae6a93654618`, and EndInv
`6a6ac376dacf086762c4afc7a20149c1b24099fd`. Retain them in detached baseline worktrees when
reproducing off/on fixtures. Missing hardware/workloads/adapters must remain BLOCKED.

The published evidence is in `lab/dimension_hosting/evidence/`. Run-006 tested behavioral heads
MTMC `7d5b45d`, emma-smp `05f87ff`, EndInv `a21ccc0`. Subsequent publication changes add only
review instructions and archived evidence, not implementation or test behavior. Historical runs remain preserved.

## Two real Fabric JVM communication experiment

The lab-only `peer_mod` runs inside two genuine Fabric server JVMs, with the normal MTMC,
emma-smp and actual EndInv mods present. Worker `a` ticks Overworld; worker `b` ticks Nether.
A lab mixin cancels `ServerLevel.tick` for the other dimensions. Vanilla still loads backing
worlds, and shared clocks/bootstrap/mod background work are not isolated by this experiment.
This is assigned **tick** isolation, not proof of exclusive world ownership or player safety.
Minecraft joins are disabled in these workers (`max-players=0`); cluster gameplay remains gated.

Both workers expose a separate loopback-only mTLS gRPC NodeProbe endpoint (25780 and 25782),
distinct from game/RCON ports (25680/25681 and 25682/25683). They asynchronously exchange
nonce-matched reports of their node, assigned dimension, fresh boot UUID, completed dimension
ticks and authenticated requester every 500ms, with one in-flight request and a two-second
deadline. Network handlers read atomic diagnostic counters, not live mutable Minecraft state.
The receiving node allowlists only its other peer's URI SAN. These reports do not mutate
shared inventories/economy or authorize transfers.

The `TWO_NODE_COMM` supplemental case boots both stacks together, checks authenticated reports
in both directions and live assigned tick progress, stops `b`, verifies `a` continues ticking
while peer requests fail, then starts a fresh `b` process and checks reconnection to its new
boot. It is single-host correctness evidence. Multi-PC performance remains BLOCKED.

Build the coordinator distribution first, then `gradle -p lab/dimension_hosting/peer_mod assemble`
using the pinned Java/Gradle versions. Generate a fresh matrix config: it includes the lab jar
and the new case. The entire matrix runs frozen as usual. To run just this case after a full
run as an unchanged diagnostic, use `tests/t_two_node_comm.py --config ... --out ...`; each
output directory must be new. The script creates fresh PKI and worlds, checks its ports are
free, and only closes its own processes. Do not add the lab mod to a player-facing server.

### Reproduce the local two-server check

This diagnostic needs Linux/WSL, Java 25, Gradle 9.7.1, Python 3, OpenSSL and the pinned real
mods/assets described in the review setup above. The config generator also requires the
baseline jars and exported client manifest, even when running this focused case. Rebuild all
changed repositories together; keep lab worlds separate from existing servers. With the
MTMC checkout as the current directory and the prepared worktrees under one lab root:

```bash
export JAVA_HOME=/absolute/path/to/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"
gradle -p coordination installDist
gradle -p lab/dimension_hosting/peer_mod assemble
python3 lab/dimension_hosting/make_config.py \
  --workspace /absolute/path/to/lab-root \
  --approved-plan lab/dimension_hosting/approved-plan.json \
  --out /absolute/path/to/new-config.json
# Review paths in new-config.json before launching; use fresh output directories.
python3 lab/dimension_hosting/tests/t_two_node_comm.py \
  --config /absolute/path/to/new-config.json \
  --out /absolute/path/to/new-two-node-result
python3 lab/dimension_hosting/run_suite.py \
  --config /absolute/path/to/new-config.json \
  --out /absolute/path/to/new-full-run
```

The focused command starts both processes itself, creates disposable certificates and worlds,
records RCON observations, stops/restarts the Nether worker, and shuts down only its own
processes. Read `result.json` and `observations.json`; inspect each worker's `server.log`,
`commands.jsonl` and `launch.json`. A PASS checks real completed dimension ticks and
nonce-matched reports with the expected authenticated requester, not merely open sockets.
Keep all six loopback ports free: game/RCON 25680/25681 and 25682/25683; peer 25780/25782.
The full suite still exits nonzero while distributed BOOT fails and acceptance cases are blocked.

The harness adds the separate lab jar and passes `-Dmtmc.lab.peer.enabled=true`, node `a`/`b`,
assigned/peer dimensions, peer ports and a disposable PKI directory. `launch.json` records the
exact JVM arguments. This switch is only for the two-process diagnostic. Normal local servers
omit the lab jar; `-Dmtmc.cluster.enabled=true` remains intentionally refused. The lab endpoints
bind to loopback and cannot be moved to another PC by changing only a port or dimension flag.
The intended production design supports server processes on one or several PCs, but safe
exclusive dimension hosting, lobby travel and shared state are still unfinished.

Latest full frozen run-006: **5 PASS, 1 FAIL, 20 BLOCKED**, 26 cases, unchanged freeze.
The two-real-JVM case PASS includes authenticated bidirectional reports, 60 survivor ticks
during peer outage (six failed RPCs), and reconnect to a fresh peer boot. The focused diagnostic
also PASS (61 ticks). All 126 Java tests and the genuine client/EndInv smoke PASS. BOOT remains
FAIL at deliberate cluster guards; multi-PC and dependent gameplay/capacity cases stay BLOCKED.
See `lab/dimension_hosting/evidence/run-006/analysis.json` and the two-node observations.
