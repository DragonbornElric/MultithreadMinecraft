# owner-stack

The owner's test stack (2026-10-02), minus the parts this VM can't match:
* MultithreadMC
* ServerCore with `dynamic.enabled: true` (the rest of `config.yml` at its 1.5.19 defaults)
* Lithium with `mixin.experimental=true`
* view distance 12, simulation distance 8, ZGC

The owner runs 20 GB of heap. The 4-vCPU lab VM has 15 GB of RAM and uses `XMX=6G`.

```bash
lab/fetch_mods.sh
CONFIGS=lab/configs/owner-stack VIEW=12 SIM=8 XMX=6G JVM_FLAGS="-XX:+UseZGC" \
  python lab/bench.py --variants vanilla+lithium+servercore mtmc+lithium+servercore
```
