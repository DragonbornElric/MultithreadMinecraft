package dev.mtmc.region;

import dev.mtmc.mixin.LevelAccessor;
import dev.mtmc.mixin.ServerChunkCacheAccessor;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * One level's region entity phase: the threads ticking its regions, the safepoint that lets one
 * of them have the whole level to itself for a moment ({@link #exclusive}), and the work deferred
 * to the level's own thread after the phase ({@link #defer}).
 *
 * <p>Region threads read anything in the level freely: nothing they share is written while they
 * run. Every write to state shared across regions goes through one of two doors:
 * <ul>
 *   <li><b>exclusive</b>: the caller waits until every other thread of the phase is parked
 *       between two entities (or is itself waiting for its turn), takes the level's two owner
 *       fields ({@code Level.thread}, {@code ServerChunkCache.mainThread}) and runs the write the
 *       vanilla way. Block changes, new entities, explosions, scheduled ticks, chunk loads,
 *       tickets, POI claims, scoreboards and advancements go here.
 *   <li><b>defer</b>: entity-section bookkeeping (an entity moving to another 16-block section,
 *       or being removed) and anything that can move an entity far (teleports, portals) waits
 *       for the end of the phase and runs in the order it was asked for, on the level's thread.
 *       So the entity-section storage is read-only while regions run.
 * </ul>
 */
public final class RegionPhase {
    private static final ThreadLocal<RegionPhase> CURRENT = new ThreadLocal<>();
    /** Region phases running now, in any level: hooks are a single volatile read when 0. */
    static final AtomicInteger RUNNING = new AtomicInteger();

    final ServerLevel level;
    final Thread ownerThread;
    /** Every level the owner thread runs this tick (this one included), or null off the parallel dimension phase. */
    final java.util.List<ServerLevel> ownerLevels;
    private final Object lock = new Object();
    private volatile boolean stopRequested;
    private Thread exclusiveOwner;
    /** Threads working in this phase (entered, not left), and how many of them are parked. */
    private int running, parked;
    final ConcurrentLinkedQueue<Runnable> deferred = new ConcurrentLinkedQueue<>();

    RegionPhase(ServerLevel level, Thread ownerThread, java.util.List<ServerLevel> ownerLevels) {
        this.level = level;
        this.ownerThread = ownerThread;
        this.ownerLevels = ownerLevels;
    }

    /**
     * With the level held exclusively, wait for another dimension's chunk load. That dimension's
     * worker may be waiting on one of this phase's levels in turn, and their owner thread is
     * stopped in this phase, so their task queues are run here meanwhile (as
     * ParallelLevelTicker.awaitForeign does for a worker). The other levels the owner thread runs
     * are borrowed for each poll; the owner is parked or is this thread, so nothing else uses them.
     */
    <T> T awaitForeign(java.util.concurrent.CompletableFuture<T> future) {
        Thread me = Thread.currentThread();
        while (!future.isDone()) {
            boolean any = level.getChunkSource().pollTask();
            if (ownerLevels != null) {
                for (ServerLevel other : ownerLevels) {
                    if (other == level) continue;
                    ServerChunkCacheAccessor cache = (ServerChunkCacheAccessor) other.getChunkSource();
                    Thread prevMain = cache.mtmc$getMainThread();
                    Thread prevThread = ((LevelAccessor) other).mtmc$getThread();
                    cache.mtmc$setMainThread(me);
                    ((LevelAccessor) other).mtmc$setThread(me);
                    try {
                        any |= other.getChunkSource().pollTask();
                    } finally {
                        cache.mtmc$setMainThread(prevMain);
                        ((LevelAccessor) other).mtmc$setThread(prevThread);
                    }
                }
            }
            if (!any) java.util.concurrent.locks.LockSupport.parkNanos(20_000L);
        }
        return future.join();
    }

    /** The phase the calling thread is ticking a region of, or null. */
    public static RegionPhase current() {
        return RUNNING.get() == 0 ? null : CURRENT.get();
    }

    /** On a region thread of this level (exclusive or not). */
    public static boolean onRegionThread(Level level) {
        RegionPhase p = current();
        return p != null && p.level == level;
    }

    /** On a region thread that doesn't hold the level to itself right now: shared writes must go through exclusive/defer. */
    public static boolean needsExclusive() {
        RegionPhase p = current();
        return p != null && p.exclusiveOwner != Thread.currentThread();
    }

    public ServerLevel level() {
        return level;
    }

    public boolean isExclusiveOwner() {
        return exclusiveOwner == Thread.currentThread();
    }

    // ── participants ────────────────────────────────────────────────

    /** A thread starts working in this phase. False if the phase has no work left for it. */
    boolean enter(java.util.function.BooleanSupplier workLeft) {
        synchronized (lock) {
            while (stopRequested) waitOn();
            if (!workLeft.getAsBoolean()) return false;
            running++;
        }
        CURRENT.set(this);
        return true;
    }

    void leave() {
        CURRENT.remove();
        synchronized (lock) {
            running--;
            lock.notifyAll();
        }
    }

    /** Wait until no participant is left (the level thread, after its own share). */
    void awaitIdle() {
        synchronized (lock) {
            while (running > 0) waitOn();
        }
    }

    /** Between two entities: park here while another thread holds the level. One volatile read otherwise. */
    void safepoint() {
        if (!stopRequested) return;
        long t0 = System.nanoTime();
        synchronized (lock) {
            if (!stopRequested || exclusiveOwner == Thread.currentThread()) return;
            parked++;
            lock.notifyAll();
            while (stopRequested) waitOn();
            parked--;
        }
        RegionStats.PARKED_NANOS.add(System.nanoTime() - t0);
    }

    // ── exclusive ───────────────────────────────────────────────────

    public void exclusive(String kind, Runnable op) {
        exclusive(kind, () -> {
            op.run();
            return null;
        });
    }

    /**
     * Run {@code op} with the whole level to the calling thread: every other thread of the phase
     * is parked between entities (or waiting for its own turn) until it returns. Reentrant.
     */
    public <T> T exclusive(String kind, Supplier<T> op) {
        Thread me = Thread.currentThread();
        if (exclusiveOwner == me) return op.get();
        long t0 = System.nanoTime();
        synchronized (lock) {
            parked++; // waiting for a turn counts as parked: another thread's turn may run meanwhile
            lock.notifyAll();
            while (stopRequested) waitOn();
            stopRequested = true;
            exclusiveOwner = me;
            parked--;
            while (parked < running - 1) waitOn();
        }
        long t1 = System.nanoTime();
        RegionStats.exclusive(kind, t1 - t0);
        Thread prevLevelThread = ((LevelAccessor) level).mtmc$getThread();
        ServerChunkCacheAccessor cache = (ServerChunkCacheAccessor) level.getChunkSource();
        Thread prevMain = cache.mtmc$getMainThread();
        ((LevelAccessor) level).mtmc$setThread(me);
        cache.mtmc$setMainThread(me);
        try {
            return op.get();
        } finally {
            ((LevelAccessor) level).mtmc$setThread(prevLevelThread);
            cache.mtmc$setMainThread(prevMain);
            RegionStats.EXCLUSIVE_HELD_NANOS.add(System.nanoTime() - t1);
            synchronized (lock) {
                exclusiveOwner = null;
                stopRequested = false;
                lock.notifyAll();
            }
        }
    }

    // ── defer ───────────────────────────────────────────────────────

    /** Run on the level's thread right after the region phase, in the order asked. */
    public void defer(String kind, Runnable task) {
        RegionStats.deferred(kind);
        deferred.add(task);
    }

    void drainDeferred() {
        Runnable task;
        while ((task = deferred.poll()) != null) task.run();
    }

    private void waitOn() {
        try {
            lock.wait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted in a region phase", e);
        }
    }

    static void setCurrent(RegionPhase p) {
        if (p == null) CURRENT.remove(); else CURRENT.set(p);
    }
}
