package dev.mtmc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ServerLevel;

/**
 * Counters for the lab: how long each level took, how long the parallel phase took (the
 * slowest level plus overhead), and how much was deferred or crossed dimensions. Written to
 * the log and to {@code mtmc-stats.json} in the server directory every
 * {@code statsIntervalSeconds}.
 */
public final class MtmcStats {
    private static final Map<String, LongAdder> DEFERRED = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> CROSS = new ConcurrentHashMap<>();
    private static final Map<String, long[]> LEVEL_NANOS = new LinkedHashMap<>(); // server thread only: {sum, max}
    private static long phases, phaseNanos, phaseMaxNanos, levelSumNanos;
    private static long windowStart = System.nanoTime();
    private static int lastWorkers;
    private static volatile String last = "{}";

    /** The most recent stats window as JSON ("{}" before the first one). */
    public static String last() {
        return last;
    }

    private MtmcStats() {}

    static void deferred(String kind) {
        DEFERRED.computeIfAbsent(kind, k -> new LongAdder()).increment();
    }

    public static long crossCount(String kind) {
        LongAdder a = CROSS.get(kind);
        return a == null ? 0 : a.sum();
    }

    static void crossLevel(String kind) {
        CROSS.computeIfAbsent(kind, k -> new LongAdder()).increment();
    }

    /** One level phase: wall time from start to end, and each level's own tick time. */
    static void phase(long start, long end, List<ServerLevel> levels, long[] nanos, int workers) {
        lastWorkers = workers;
        long wall = end - start;
        phases++;
        phaseNanos += wall;
        phaseMaxNanos = Math.max(phaseMaxNanos, wall);
        for (int i = 0; i < levels.size(); i++) {
            long[] acc = LEVEL_NANOS.computeIfAbsent(levels.get(i).dimension().identifier().toString(), k -> new long[2]);
            acc[0] += nanos[i];
            acc[1] = Math.max(acc[1], nanos[i]);
            levelSumNanos += nanos[i];
        }
        int interval = Mtmc.config().statsIntervalSeconds;
        if (interval > 0 && end - windowStart >= interval * 1_000_000_000L) {
            flush(end);
        }
    }

    private static void flush(long now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window_seconds", (now - windowStart) / 1e9);
        out.put("parallel", Mtmc.config().parallelDimensions);
        out.put("threads", lastWorkers);
        out.put("phases", phases);
        out.put("phase_avg_ms", phases == 0 ? 0 : phaseNanos / 1e6 / phases);
        out.put("phase_max_ms", phaseMaxNanos / 1e6);
        // Sum of the levels' own times over the phase's wall time: 1.0 = no gain, N = N levels' work in the time of one.
        out.put("overlap", phaseNanos == 0 ? 0 : (double) levelSumNanos / phaseNanos);
        Map<String, Object> lv = new TreeMap<>();
        LEVEL_NANOS.forEach((k, v) -> lv.put(k, Map.of("avg_ms", phases == 0 ? 0 : v[0] / 1e6 / phases, "max_ms", v[1] / 1e6)));
        out.put("levels", lv);
        out.put("deferred", snapshot(DEFERRED));
        out.put("cross_level", snapshot(CROSS));
        String json = toJson(out);
        last = json;
        Mtmc.LOGGER.info("[stats] {}", json);
        try {
            Path tmp = Path.of("mtmc-stats.json.tmp");
            Files.writeString(tmp, json + "\n");
            Files.move(tmp, Path.of("mtmc-stats.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Mtmc.LOGGER.warn("Could not write mtmc-stats.json", e);
        }
        phases = phaseNanos = phaseMaxNanos = levelSumNanos = 0;
        LEVEL_NANOS.clear();
        windowStart = now;
    }

    private static Map<String, Long> snapshot(Map<String, LongAdder> m) {
        Map<String, Long> out = new TreeMap<>();
        m.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    @SuppressWarnings("unchecked")
    static String toJson(Object o) {
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<Object, Object>) m).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(toJson(String.valueOf(e.getKey()))).append(':').append(toJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof String s) return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        if (o instanceof Double d) return String.format(java.util.Locale.ROOT, "%.3f", d);
        return String.valueOf(o);
    }
}
