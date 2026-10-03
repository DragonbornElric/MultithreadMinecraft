# Dimension-hosting prototype evidence

These are historical results from disposable Linux cloud worlds, not a release acceptance report.

| Run | PASS | FAIL | BLOCKED | Frozen source/config/artifacts unchanged |
| --- | ---: | ---: | ---: | --- |
| 001 | 3 | 2 | 20 | yes |
| 002 | 3 | 2 | 20 | yes |
| 003 | 4 | 1 | 20 | yes |
| 004 | 4 | 1 | 20 | yes |
| 005 | 4 | 2 | 20 | yes |
| 006 | 5 | 1 | 20 | yes |

Run-006 is the latest behavioral run: 26 cases, 126 passing Java tests (14 ledger/outbox/session,
9 real mTLS transport/admission, 103 existing emma-smp tests), zero Java failures/errors/skips,
and passing real Fabric/EndInv client smoke. All five supplemental cases PASS. The 21 approved
acceptance cases remain one FAIL (distributed BOOT) and 20 BLOCKED; the runner exits 1.

`TWO_NODE_COMM` runs two genuine Fabric JVMs with the real MTMC/emma-smp/EndInv stack. Worker
`a` ticks Overworld and `b` ticks Nether. Both exchange direct mTLS reports of dimension ticks
and authenticated requester identity. While `b` is stopped, `a` advances 60 additional ticks
and records six RPC failures; after a fresh `b` process starts, both reconnect with its new boot
UUID. Vanilla backing worlds still load, and shared clocks/bootstrap/mod background work remain:
this is diagnostic tick isolation and communication, not exclusive load/save ownership or player
transfer. Multi-PC and capacity claims remain blocked. The focused diagnostic also passes.

Read [run-006/analysis.json](run-006/analysis.json), then
[the two-node observations](run-006/TWO_NODE_COMM/observations.json) and per-case records.
The packaging failure in run-005 is preserved with both server error logs and its diagnosis:
the Fabric entrypoint was inside a reserved Mixin package; the narrowed package fixes startup.

Each published full run has `freeze.json` with its exact source/artifact/configuration manifest.
The per-case duplicated `configuration` object becomes a reference to this shared config; other
fields and the original result SHA256 are retained. Absolute cloud paths show provenance;
`published_evidence` identifies files archived here. `archive-manifest.json` hashes the archive.
Tested behavioral commit IDs precede subsequent documentation/evidence publication commits.

Runs 001–003 have summaries/analysis only; their detailed evidence remains in the original
workspace. Run-005 archives the failed cases; run-004 and run-006 archive all per-case records. The unchanged post-run cursor diagnostic is recorded separately; it does
not change run-002's FAIL into PASS. Its original configuration is not republished in full.

No worlds, databases, jars, credentials, PKI private keys or certificates are included. Test
commands/transcripts contain disposable offline player state and fixture settings. Downloaded
runtime jars are identified by hashes, not redistributed.

The smoke case creates/selects a real EndInv, deposits 64 diamonds through the actual menu,
checks the actual codec and player snapshot, and runs MTMC's cross-level selftest. Each of
STACK_SMOKE, BASE0 and BASE1 reports 120 successful chunk loads, zero failures, and 120 tickets
handed to their owning level. This does not exercise distributed restoration. Synthetic mob
samples run after the single fixture client disconnects; no 100/300-player capacity claim is
supported. Multi-PC cases remained BLOCKED by explicit user instruction. Headless/offline
runtime limitations and unfinished gameplay adapters are described in the analysis.
