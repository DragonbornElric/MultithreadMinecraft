package dev.mtmc.lag;

import dev.mtmc.Mtmc;
import dev.mtmc.MtmcConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.Consumer;
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
 *
 * <p><b>Throttle</b> (config lagThrottle): a chunk over budget is slowed, not cancelled. At
 * level k it ticks on 1 in 2^k game ticks (its own phase, so throttled chunks don't all wake
 * together); on the other ticks it is "not ticking", the state vanilla gives loaded chunks
 * outside simulation distance: scheduled block/fluid ticks wait in their queue in order,
 * block events are rescheduled, block entities, entities (never players, nor vehicles
 * carrying one) and random ticks are skipped. See ServerLevelLagMixin / LevelTicksLagMixin.
 */
public final class LagTracker {
    public static final int WINDOW_TICKS = 20;
    private static final double ALPHA = 0.8; // weight of the old average per 1 s window
    private static final double PEAK_DECAY = 0.95;

    private final Long2ObjectOpenHashMap<ChunkLag> chunks = new Long2ObjectOpenHashMap<>();
    private int depth;
    private int ticksInWindow;
    private long windowNanos;
    private long gameTime;
    private int throttledChunks;
    /** Called (on the level's tick thread) when a chunk's throttle level changes: chunk, old level. */
    private Consumer<ThrottleChange> onThrottleChange = c -> {};
    private java.util.function.DoubleSupplier serverMspt = () -> 0;

    /** ThrottleChange level meaning "frozen". */
    public static final int FROZEN = 99;

    public record ThrottleChange(ChunkLag chunk, int from, int to) {}

    /** Entities waiting for room under a chunk's cap, in arrival order (EntityCaps). */
    public final java.util.ArrayDeque<net.minecraft.world.entity.Entity> pending = new java.util.ArrayDeque<>();

    public void hooks(Consumer<ThrottleChange> onThrottleChange, java.util.function.DoubleSupplier serverMspt) {
        this.onThrottleChange = onThrottleChange;
        this.serverMspt = serverMspt;
    }

    /** Called at the start of every level tick. */
    public void startTick(long gameTime) {
        this.gameTime = gameTime;
    }

    /** True if this chunk is throttled and this is not one of its ticks. */
    public boolean skipNow(long chunkPos) {
        if (throttledChunks == 0) return false;
        ChunkLag c = chunks.get(chunkPos);
        if (c == null || (c.throttle == 0 && !c.frozen)) return false;
        if (c.frozen) return true;
        int period = 1 << c.throttle;
        long phase = it.unimi.dsi.fastutil.HashCommon.mix(chunkPos);
        return ((gameTime + phase) & (period - 1)) != 0;
    }

    public int throttledChunks() {
        return throttledChunks;
    }

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
        /** Throttle level: ticks on 1 in 2^level game ticks. 0 = full speed. */
        public int throttle;
        /** Frozen: no ticks at all until /mtmc lag release. */
        public boolean frozen;
        int overWindows, underWindows, freezeWindows;
        /** Entities waiting for room under the chunk's cap (EntityCaps). */
        public int delayed;

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

    /** Count without timing (light updates). */
    public void count(long chunkPos, LagCategory cat) {
        if (!enabled()) return;
        ChunkLag c = chunks.get(chunkPos);
        if (c == null) {
            c = new ChunkLag(chunkPos);
            chunks.put(chunkPos, c);
        }
        c.winCount[cat.ordinal()]++;
    }

    public ChunkLag getOrCreate(long chunkPos) {
        ChunkLag c = chunks.get(chunkPos);
        if (c == null) {
            c = new ChunkLag(chunkPos);
            chunks.put(chunkPos, c);
        }
        return c;
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
            updateThrottle(c);
            if (c.idleWindows > 30 && c.avgTotalMs < 0.001 && c.throttle == 0 && !c.frozen && c.delayed == 0) it.remove();
        }
    }

    /**
     * Escalate a chunk that stays over budget (while the server is busy, or always when it is
     * far over); de-escalate once it would stay well under budget at the next faster level.
     */
    private void updateThrottle(ChunkLag c) {
        MtmcConfig cfg = Mtmc.config();
        int before = c.throttle;
        if (!cfg.lagThrottle) {
            if (c.throttle != 0 || c.frozen) {
                boolean wasFrozen = c.frozen;
                c.frozen = false;
                setThrottle(c, 0);
                onThrottleChange.accept(new ThrottleChange(c, wasFrozen ? FROZEN : before, 0));
            }
            return;
        }
        if (c.frozen) return; // sticky until /mtmc lag release
        boolean busy = serverMspt.getAsDouble() >= cfg.lagServerBusyMs;
        double light = c.avgCount[LagCategory.LIGHT.ordinal()];
        // light work happens off the tick thread, so it has its own budget (updates per tick) and no busy gate
        boolean lightOver = cfg.lagLightBudget > 0 && light > cfg.lagLightBudget;
        boolean over = lightOver || c.avgTotalMs > cfg.lagChunkBudgetMs && (busy || c.avgTotalMs > cfg.lagHardBudgetMs);
        // what it would cost one level faster (2x the ticks)
        boolean wellUnder = c.avgTotalMs * 2 < cfg.lagChunkBudgetMs * 0.5 && (cfg.lagLightBudget <= 0 || light * 2 < cfg.lagLightBudget * 0.5);
        // last resort: still far over at the slowest level
        if (cfg.lagFreeze && c.throttle >= cfg.lagMaxThrottle
            && (c.avgTotalMs > cfg.lagFreezeMs || cfg.lagLightBudget > 0 && light > cfg.lagLightBudget * 4)) {
            if (++c.freezeWindows >= 5) {
                c.frozen = true;
                c.freezeWindows = 0;
                onThrottleChange.accept(new ThrottleChange(c, before, FROZEN));
                return;
            }
        } else {
            c.freezeWindows = 0;
        }
        if (over) {
            c.underWindows = 0;
            if (++c.overWindows >= 2 && c.throttle < cfg.lagMaxThrottle) {
                c.overWindows = 0;
                setThrottle(c, c.throttle + 1);
            }
        } else {
            c.overWindows = 0;
            if (c.throttle > 0 && wellUnder && ++c.underWindows >= 5) {
                c.underWindows = 0;
                setThrottle(c, c.throttle - 1);
            }
        }
        if (before != c.throttle) onThrottleChange.accept(new ThrottleChange(c, before, c.throttle));
    }

    private void setThrottle(ChunkLag c, int level) {
        if (c.throttle == 0 && level > 0) throttledChunks++;
        if (c.throttle > 0 && level == 0) throttledChunks--;
        c.throttle = level;
    }

    /** Back to full speed everywhere (/mtmc lag release). */
    public void releaseAll() {
        for (ChunkLag c : chunks.values()) {
            c.frozen = false;
            c.freezeWindows = 0;
            setThrottle(c, 0);
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
        throttledChunks = 0;
    }
}
