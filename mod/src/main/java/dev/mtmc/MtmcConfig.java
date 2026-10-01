package dev.mtmc;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** {@code config/multithreadmc.properties}. Read once at server start. */
public final class MtmcConfig {
    /** Tick each dimension on its own thread. Off = vanilla behaviour (the mod only counts). */
    public final boolean parallelDimensions;
    /** Run command blocks after the parallel phase (a command may touch any dimension). */
    public final boolean deferCommandBlocks;
    /** Log the first stack trace of each kind of cross-dimension access seen during the parallel phase. */
    public final boolean logCrossLevelAccess;
    /** Seconds between stats lines in the log and {@code mtmc-stats.json}; 0 = off. */
    public final int statsIntervalSeconds;

    private MtmcConfig(Properties p) {
        parallelDimensions = bool(p, "parallelDimensions", true);
        deferCommandBlocks = bool(p, "deferCommandBlocks", true);
        logCrossLevelAccess = bool(p, "logCrossLevelAccess", true);
        statsIntervalSeconds = Integer.parseInt(p.getProperty("statsIntervalSeconds", "30").trim());
    }

    private static boolean bool(Properties p, String key, boolean def) {
        return Boolean.parseBoolean(p.getProperty(key, Boolean.toString(def)).trim());
    }

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
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(file.getParent());
                try (Writer w = Files.newBufferedWriter(file)) {
                    w.write("# MultithreadMC\n"
                        + "parallelDimensions=" + cfg.parallelDimensions + "\n"
                        + "deferCommandBlocks=" + cfg.deferCommandBlocks + "\n"
                        + "logCrossLevelAccess=" + cfg.logCrossLevelAccess + "\n"
                        + "statsIntervalSeconds=" + cfg.statsIntervalSeconds + "\n");
                }
            } catch (IOException e) {
                Mtmc.LOGGER.warn("Could not write {}", file, e);
            }
        }
        return cfg;
    }
}
