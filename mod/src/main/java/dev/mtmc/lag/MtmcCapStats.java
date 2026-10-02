package dev.mtmc.lag;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Cumulative counters of what the entity caps did (stats file and /mtmc lag caps). */
public final class MtmcCapStats {
    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();

    private MtmcCapStats() {}

    static void count(String what) {
        COUNTS.computeIfAbsent(what, k -> new LongAdder()).increment();
    }

    public static Map<String, Long> snapshot() {
        Map<String, Long> out = new TreeMap<>();
        COUNTS.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }
}
