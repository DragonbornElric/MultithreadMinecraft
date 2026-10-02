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
                    + "logCrossLevelAccess=" + logCrossLevelAccess + "\n"
                    + "statsIntervalSeconds=" + statsIntervalSeconds + "\n");
            }
        } catch (IOException e) {
            Mtmc.LOGGER.warn("Could not write {}", file, e);
        }
    }
}
