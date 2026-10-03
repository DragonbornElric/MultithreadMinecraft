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
