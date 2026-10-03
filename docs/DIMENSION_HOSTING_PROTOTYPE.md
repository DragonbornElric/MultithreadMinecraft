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
