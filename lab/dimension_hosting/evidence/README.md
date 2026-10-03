# Dimension-hosting prototype evidence

These are historical results from disposable Linux cloud worlds, not a release acceptance report.

| Run | PASS | FAIL | BLOCKED | Frozen source/config/artifacts unchanged |
| --- | ---: | ---: | ---: | --- |
| 001 | 3 | 2 | 20 | yes |
| 002 | 3 | 2 | 20 | yes |
| 003 | 4 | 1 | 20 | yes |
| 004 | 4 | 1 | 20 | yes |

Run-004 is the final behavioral run: 25 cases, 126 passing Java tests (14 ledger/outbox/session,
9 real mTLS transport/admission, 103 existing emma-smp tests), no failures/errors/skips in
those Java suites, and a passing real Fabric/EndInv client smoke case. The 21 approved
acceptance cases comprise one FAIL (distributed BOOT) and 20 BLOCKED. All four supplemental
cases PASS. The runner exits 1 because distributed gameplay acceptance is unmet.

Read [run-004/analysis.json](run-004/analysis.json), then the per-case records and their
`published_evidence` paths. `freeze.json` contains the exact source, artifact and configuration
manifest used during the run. Every per-case field is retained, except the duplicated
`configuration` object is replaced with a reference to that shared frozen config. The original
result file's SHA256 is recorded. Original absolute cloud evidence paths are retained for
provenance; `published_evidence` identifies transcripts/XML available in this repository.
`archive-manifest.json` hashes the published run-004 files. Historical source hashes and tested
commit IDs intentionally precede this documentation/evidence publication commit.

Only summaries/analysis are published for earlier runs. Their detailed evidence remains in the
original workspace. The unchanged post-run cursor diagnostic is recorded separately; it does
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
