package dev.mtmc;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** {@code config/multithreadmc.properties}. parallelDimensions and threads can change at runtime (/mtmc). */
public final class MtmcConfig {
    /** Tick dimensions on worker threads. Off = vanilla behaviour (the mod only counts). /mtmc on|off. */
    public volatile boolean parallelDimensions;
    /**
     * Worker threads for the level phase. 0 = one per dimension (3 on a vanilla world). Fewer
     * threads than dimensions: each worker ticks a group, balanced by recent tick time. Below 2,
     * the levels tick on the server thread as in vanilla. /mtmc threads N.
     */
    public volatile int threads;
    /** Time every chunk's ticks by category (/mtmc lag). Costs two clock reads per ticked thing. /mtmc lag on|off. */
    public volatile boolean lagAccounting;
    /** Slow chunks that stay over budget (see LagTracker). Off by default: it changes how fast machines run. */
    public volatile boolean lagThrottle;
    /** A chunk over this many ms per tick (5 s average) is over budget. */
    public volatile double lagChunkBudgetMs;
    /** Throttle over-budget chunks only while the server's smoothed MSPT is at least this... */
    public volatile double lagServerBusyMs;
    /** ...or always, when the chunk alone is over this. */
    public volatile double lagHardBudgetMs;
    /** Slowest throttle level: 1 tick in 2^this (5 = 1 in 32). */
    public volatile int lagMaxThrottle;
    /** Light updates per tick (5 s average) a chunk may queue before it counts as over budget; 0 = ignore light. */
    public volatile double lagLightBudget;
    /** Freeze (no ticks until /mtmc lag release) a chunk still over lagFreezeMs at the slowest throttle level for 5 s. */
    public volatile boolean lagFreeze;
    public volatile double lagFreezeMs;
    /** Per-chunk entity caps for new entities (EntityCaps). Off by default. */
    public volatile boolean lagCaps;
    public volatile int capItems, capTnt, capFallingBlocks, capVehicles, capArmorStands, capMobs;
    /** Run command blocks after the parallel phase (a command may touch any dimension). */
    public final boolean deferCommandBlocks;
    /** Log the first stack trace of each kind of cross-dimension access seen during the parallel phase. */
    public final boolean logCrossLevelAccess;
    /** Seconds between stats lines in the log and {@code mtmc-stats.json}; 0 = off. */
    public final int statsIntervalSeconds;

    private MtmcConfig(Properties p) {
        parallelDimensions = bool(p, "parallelDimensions", true);
        threads = Integer.parseInt(p.getProperty("threads", "0").trim());
        deferCommandBlocks = bool(p, "deferCommandBlocks", true);
        lagAccounting = bool(p, "lagAccounting", true);
        lagThrottle = bool(p, "lagThrottle", false);
        lagChunkBudgetMs = Double.parseDouble(p.getProperty("lagChunkBudgetMs", "2.0").trim());
        lagServerBusyMs = Double.parseDouble(p.getProperty("lagServerBusyMs", "40.0").trim());
        lagHardBudgetMs = Double.parseDouble(p.getProperty("lagHardBudgetMs", "10.0").trim());
        lagMaxThrottle = Integer.parseInt(p.getProperty("lagMaxThrottle", "5").trim());
        lagLightBudget = Double.parseDouble(p.getProperty("lagLightBudget", "2000").trim());
        lagFreeze = bool(p, "lagFreeze", true);
        lagFreezeMs = Double.parseDouble(p.getProperty("lagFreezeMs", "20.0").trim());
        lagCaps = bool(p, "lagCaps", false);
        capItems = Integer.parseInt(p.getProperty("capItems", "400").trim());
        capTnt = Integer.parseInt(p.getProperty("capTnt", "300").trim());
        capFallingBlocks = Integer.parseInt(p.getProperty("capFallingBlocks", "200").trim());
        capVehicles = Integer.parseInt(p.getProperty("capVehicles", "64").trim());
        capArmorStands = Integer.parseInt(p.getProperty("capArmorStands", "64").trim());
        capMobs = Integer.parseInt(p.getProperty("capMobs", "300").trim());
        logCrossLevelAccess = bool(p, "logCrossLevelAccess", true);
        statsIntervalSeconds = Integer.parseInt(p.getProperty("statsIntervalSeconds", "30").trim());
    }

    private static boolean bool(Properties p, String key, boolean def) {
        return Boolean.parseBoolean(p.getProperty(key, Boolean.toString(def)).trim());
    }

    private Path file;

    static MtmcConfig load(Path file) {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                p.load(r);
            } catch (IOException e) {
                Mtmc.LOGGER.warn("Could not read {}, using defaults", file, e);
            }
        }
        MtmcConfig cfg = new MtmcConfig(p);
        cfg.file = file;
        if (!Files.exists(file)) cfg.save();
        return cfg;
    }

    /** Write the current values back (the /mtmc command keeps its changes across restarts). */
    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                w.write("# MultithreadMC\n"
                    + "# threads: 0 = one per dimension\n"
                    + "parallelDimensions=" + parallelDimensions + "\n"
                    + "threads=" + threads + "\n"
                    + "deferCommandBlocks=" + deferCommandBlocks + "\n"
                    + "lagAccounting=" + lagAccounting + "\n"
                    + "lagThrottle=" + lagThrottle + "\n"
                    + "lagChunkBudgetMs=" + lagChunkBudgetMs + "\n"
                    + "lagServerBusyMs=" + lagServerBusyMs + "\n"
                    + "lagHardBudgetMs=" + lagHardBudgetMs + "\n"
                    + "lagMaxThrottle=" + lagMaxThrottle + "\n"
                    + "lagLightBudget=" + lagLightBudget + "\n"
                    + "lagFreeze=" + lagFreeze + "\n"
                    + "lagFreezeMs=" + lagFreezeMs + "\n"
                    + "# per-chunk caps for NEW entities (0 = no cap); TNT, falling blocks and items wait, vehicles/armor stands drop as items, mobs are refused\n"
                    + "lagCaps=" + lagCaps + "\n"
                    + "capItems=" + capItems + "\n"
                    + "capTnt=" + capTnt + "\n"
                    + "capFallingBlocks=" + capFallingBlocks + "\n"
                    + "capVehicles=" + capVehicles + "\n"
                    + "capArmorStands=" + capArmorStands + "\n"
                    + "capMobs=" + capMobs + "\n"
                    + "logCrossLevelAccess=" + logCrossLevelAccess + "\n"
                    + "statsIntervalSeconds=" + statsIntervalSeconds + "\n");
            }
        } catch (IOException e) {
            Mtmc.LOGGER.warn("Could not write {}", file, e);
        }
    }
}
