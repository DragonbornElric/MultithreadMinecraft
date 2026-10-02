package dev.mtmc.region;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Counters for region ticking (/mtmc regions, the stats file). */
public final class RegionStats {
    private RegionStats() {}

    /** Level ticks that ran their entities as regions, and ones that fell back to the vanilla loop (by reason). */
    public static final LongAdder PHASES = new LongAdder();
    public static final Map<String, LongAdder> SERIAL = new ConcurrentHashMap<>();
    /** Regions and entities ticked in region phases; the biggest region's entities (for the share). */
    public static final LongAdder REGIONS = new LongAdder();
    public static final LongAdder ENTITIES = new LongAdder();
    public static final LongAdder BIGGEST = new LongAdder();
    /** Wall time of the region phases, and the sum of the time threads spent ticking regions in them. */
    public static final LongAdder PHASE_NANOS = new LongAdder();
    public static final LongAdder WORK_NANOS = new LongAdder();
    /** Exclusive sections: count by kind, time waiting for the others to park, time held. */
    public static final Map<String, LongAdder> EXCLUSIVE = new ConcurrentHashMap<>();
    public static final LongAdder EXCLUSIVE_WAIT_NANOS = new LongAdder();
    public static final LongAdder EXCLUSIVE_HELD_NANOS = new LongAdder();
    public static final LongAdder PARKED_NANOS = new LongAdder();
    public static final Map<String, LongAdder> DEFERRED = new ConcurrentHashMap<>();
    /** An entity whose tick ended in a cell another region owns: the buffer was too small (should stay 0). */
    public static final LongAdder ESCAPES = new LongAdder();

    static void exclusive(String kind, long waitNanos) {
        EXCLUSIVE.computeIfAbsent(kind, k -> new LongAdder()).increment();
        EXCLUSIVE_WAIT_NANOS.add(waitNanos);
    }

    static void deferred(String kind) {
        DEFERRED.computeIfAbsent(kind, k -> new LongAdder()).increment();
    }

    static void serial(String reason) {
        SERIAL.computeIfAbsent(reason, k -> new LongAdder()).increment();
    }

    /** A snapshot for the stats file and /mtmc regions; resets nothing. */
    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        long phases = PHASES.sum();
        out.put("phases", phases);
        out.put("serial", sums(SERIAL));
        out.put("regions_avg", phases == 0 ? 0.0 : (double) REGIONS.sum() / phases);
        out.put("entities_avg", phases == 0 ? 0.0 : (double) ENTITIES.sum() / phases);
        out.put("biggest_share", ENTITIES.sum() == 0 ? 0.0 : (double) BIGGEST.sum() / ENTITIES.sum());
        out.put("phase_avg_ms", phases == 0 ? 0.0 : PHASE_NANOS.sum() / 1e6 / phases);
        out.put("overlap", PHASE_NANOS.sum() == 0 ? 0.0 : (double) WORK_NANOS.sum() / PHASE_NANOS.sum());
        out.put("exclusive", sums(EXCLUSIVE));
        out.put("exclusive_wait_ms", EXCLUSIVE_WAIT_NANOS.sum() / 1e6);
        out.put("exclusive_held_ms", EXCLUSIVE_HELD_NANOS.sum() / 1e6);
        out.put("parked_ms", PARKED_NANOS.sum() / 1e6);
        out.put("deferred", sums(DEFERRED));
        out.put("escapes", ESCAPES.sum());
        return out;
    }

    public static void reset() {
        for (LongAdder a : new LongAdder[] {PHASES, REGIONS, ENTITIES, BIGGEST, PHASE_NANOS, WORK_NANOS, EXCLUSIVE_WAIT_NANOS,
            EXCLUSIVE_HELD_NANOS, PARKED_NANOS, ESCAPES}) a.reset();
        SERIAL.clear();
        EXCLUSIVE.clear();
        DEFERRED.clear();
    }

    private static Map<String, Long> sums(Map<String, LongAdder> m) {
        Map<String, Long> out = new TreeMap<>();
        m.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }
}
