package dev.mtmc.lag;

import dev.mtmc.Mtmc;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tick time per chunk for one level, by {@link LagCategory}.
 *
 * <p>Only the level's own tick thread writes (the server thread, or the level's
 * MultithreadMC worker), so there is no locking; readers (/mtmc lag, the stats file) run on
 * the server thread between ticks, when no worker is running.
 *
 * <p>Time is charged to the chunk where the ticked thing is when its tick starts: the entity's
 * chunk, the block entity's, the scheduled tick's position, the block event's, the chunk being
 * random-ticked. Neighbour updates, explosions, item drops etc. caused by that tick are part
 * of it, so a redstone chain is charged to the clock driving it. Nested ticks (an entity tick
 * inside a block tick, say) are counted once, in the outer one.
 *
 * <p>Every {@link #WINDOW_TICKS} ticks the window is folded into an exponential average
 * (about 5 s horizon) and a decaying peak.
 */
public final class LagTracker {
    public static final int WINDOW_TICKS = 20;
    private static final double ALPHA = 0.8; // weight of the old average per 1 s window
    private static final double PEAK_DECAY = 0.95;

    private final Long2ObjectOpenHashMap<ChunkLag> chunks = new Long2ObjectOpenHashMap<>();
    private int depth;
    private int ticksInWindow;
    private long windowNanos;

    /** A chunk's numbers. ms values are per game tick. */
    public static final class ChunkLag {
        public final long pos;
        final long[] winNanos = new long[LagCategory.ALL.length];
        final int[] winCount = new int[LagCategory.ALL.length];
        public final double[] avgMs = new double[LagCategory.ALL.length];
        public final double[] avgCount = new double[LagCategory.ALL.length];
        public double avgTotalMs;
        public double peakMs;
        int idleWindows;

        ChunkLag(long pos) {
            this.pos = pos;
        }
    }

    public static boolean enabled() {
        return Mtmc.config().lagAccounting;
    }

    /** Start timing; returns 0 when not timing (disabled, or nested inside another timed tick). */
    public long begin() {
        if (depth++ > 0 || !enabled()) return 0L;
        return System.nanoTime();
    }

    public void end(long start, long chunkPos, LagCategory cat) {
        depth--;
        if (start == 0L) return;
        long dt = System.nanoTime() - start;
        ChunkLag c = chunks.get(chunkPos);
        if (c == null) {
            c = new ChunkLag(chunkPos);
            chunks.put(chunkPos, c);
        }
        c.winNanos[cat.ordinal()] += dt;
        c.winCount[cat.ordinal()]++;
        windowNanos += dt;
    }

    /** Called once at the end of every level tick. */
    public void endTick() {
        depth = 0; // a tick that threw mid-way must not leave timing disabled
        if (++ticksInWindow < WINDOW_TICKS) return;
        double ticks = ticksInWindow;
        ticksInWindow = 0;
        windowNanos = 0;
        var it = chunks.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            ChunkLag c = it.next().getValue();
            double total = 0;
            boolean active = false;
            for (int i = 0; i < c.winNanos.length; i++) {
                double ms = c.winNanos[i] / 1e6 / ticks;
                c.avgMs[i] = c.avgMs[i] * ALPHA + ms * (1 - ALPHA);
                c.avgCount[i] = c.avgCount[i] * ALPHA + c.winCount[i] / ticks * (1 - ALPHA);
                total += c.avgMs[i];
                active |= c.winCount[i] > 0;
                c.winNanos[i] = 0;
                c.winCount[i] = 0;
            }
            c.avgTotalMs = total;
            c.peakMs = Math.max(c.peakMs * PEAK_DECAY, total);
            c.idleWindows = active ? 0 : c.idleWindows + 1;
            if (c.idleWindows > 30 && c.avgTotalMs < 0.001) it.remove();
        }
    }

    /** Chunks by average ms/tick, worst first. */
    public List<ChunkLag> top(int n) {
        List<ChunkLag> all = new ArrayList<>(chunks.values());
        all.sort(Comparator.comparingDouble((ChunkLag c) -> c.avgTotalMs).reversed());
        return all.subList(0, Math.min(n, all.size()));
    }

    public ChunkLag get(long chunkPos) {
        return chunks.get(chunkPos);
    }

    public double totalAvgMs() {
        double t = 0;
        for (ChunkLag c : chunks.values()) t += c.avgTotalMs;
        return t;
    }

    public void reset() {
        chunks.clear();
    }
}
