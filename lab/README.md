# MultithreadMC lab (Minecraft 26.2)

A headless Fabric 26.2 server with RCON, a benchmark that compares mod variants under the same
load, and a stress/correctness run driven by the Emma bridge bot.

## Requirements

* Linux, a JDK 25 (`JAVA_HOME`, and `java` on `PATH` for the server)
* Python 3.11+ with `websocket-client` (only needed for the bot)
* `xvfb-run` and Mesa (software GL) for the bot client

## Pieces

| File | What it does |
| --- | --- |
| `setup_server.sh [lab_dir]` | Creates `$LAB_DIR/server` (default `/tmp/mtmc-lab`) with the following settings, applied to every variant: Fabric 26.2 / loader 0.19.5, offline mode, a normal world, RCON on 25575 (password `mtmclab`), command blocks on, `pause-when-empty-seconds=0`. |
| `run_server.sh <variant>` | Starts the server with `vanilla` (Fabric only), `mtmc` / `mtmc@N` (the mod, N worker threads, default one per dimension), `mtmc-off` (mod loaded, parallel off) or `async` (the Async mod from `$LAB_DIR/jars/`). Waits for `Done`. Append `+slug` for other mods (`mtmc+lithium+servercore`; Fabric API is added automatically). Env: `CONFIGS` (a config dir copied in, e.g. `lab/configs/owner-stack`), `VIEW`, `SIM`, `XMX`, `JVM_FLAGS`. |
| `fetch_mods.sh [slug ...]` | Downloads the newest Fabric 26.2 build of each Modrinth project into `$LAB_DIR/jars/all/` (default: fabric-api, lithium, servercore, moonrise-opt). |
| `configs/owner-stack/` | The owner's test stack: ServerCore dynamic on, Lithium experimental on (see its README). |
| `stop_server.sh` | RCON `stop`, then kills the server if needed. |
| `rcon.py` | `python lab/rcon.py "mtmc status" "tick query"` |
| `bench.py` | The benchmark (below) |
| `stress_bot.py` | Bot stress and correctness scenarios (below) |
| `bot/start_bot.sh`, `bot/stop_bot.sh` | The Emma bridge bot (EmmaMinecraft261, branch `mc-26.2`, used as-is) under Xvfb, joining as `EmmaBot`. |

## Benchmark

```bash
export PATH=$JAVA_HOME/bin:$PATH
lab/setup_server.sh
(cd mod && ./gradlew build)
python lab/bench.py --variants vanilla mtmc@2 mtmc mtmc-off --mobs 1000
```

Each variant gets the same setup:
* a fresh world from the same seed
* an 80×80 glass pen at y=200, force-loaded, in each dimension
* `--mobs` animals summoned into each pen (cow, sheep, chicken, pig, persistent)
* no cramming, no natural spawning

Then the run:
1. 15 s to settle
2. four `/tick query` samples at 20 TPS (`mspt_avg`, `mspt_p95`)
3. `/tick sprint 1200` (`sprint.tps`)

`--skew 0.1` gives the other dimensions a tenth of the first one's mobs, i.e. an
Overworld-heavy server. Results go to `$LAB_DIR/results.jsonl`, including the mod's
per-level times and `overlap`: the sum of the levels' tick times over the phase's wall time,
so 1.0 means no gain.

## Bot stress run

```bash
pip install websocket-client
python lab/stress_bot.py --variant mtmc            # restarts server + bot, all scenarios
python lab/stress_bot.py --bot-running --scenarios pearl portal_walk   # reuse what is up
```

The bot checkout is a clone of `mc-26.2` in `$LAB_DIR/emma262`, cloned on first use. Its
source is never modified; only its build output and Fabric run directory are written. The
lab server has no EndInv server side, so the bot gets EmmaMinecraft261's empty EndInv
stand-in (`tools/combat_lab/endinv_stub`), compiled against the 26.2 jar.

Every scenario runs with mob pens loaded in all three dimensions:

| Scenario | Path it exercises | Pass condition |
| --- | --- | --- |
| `command_blocks` | Deferred command blocks writing into another dimension every tick | Runs == game ticks, exactly (ticks frozen while reading) |
| `portal_stream` | 200 chickens through an Overworld portal (deferred entity portals) | All arrive in the Nether |
| `pearl` | Pearls landing in one dimension while their owner (the bot) is in the other | The bot is pulled across every time |
| `portal_walk` | The bot walks into a portal, then back through the one it arrived in | Every trip changes dimension |
| `hero`, `nether_hero` | The bot plays (GOAP hero mode: mining, combat, exploring, chunk generation) | Alive, server alive, no exceptions |

Each line records MSPT, the mod's deferred/cross-level counters, and any
`Exception`/`Can't keep up` lines the server logged during that scenario. Results go to
`$LAB_DIR/stress.jsonl`.
