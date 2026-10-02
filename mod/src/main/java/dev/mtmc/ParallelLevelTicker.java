package dev.mtmc;

import dev.mtmc.mixin.LevelAccessor;
import dev.mtmc.mixin.ServerChunkCacheAccessor;
import dev.mtmc.mixin.ServerLevelInvoker;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Ticks every dimension of the server at the same time: one thread per dimension, or
 * {@code threads} workers each ticking a group of dimensions.
 *
 * <p>Vanilla's {@code MinecraftServer.tickChildren} ticks the levels one after another. The
 * levels share almost no mutable state, and vanilla already fences each level off by thread:
 * {@code Level.thread} (block entities read off it return null) and
 * {@code ServerChunkCache.mainThread} (chunk loads off it are handed to that thread's queue).
 * For the parallel phase each worker becomes the owner of its level by swapping both fields,
 * and gives them back to the server thread before the phase ends.
 *
 * <p>What does cross dimensions is either done before the phase (the overworld clock, which
 * the other dimensions read their game time from), deferred until after it, on the server
 * thread, in the order it was asked for (portals, teleports into or out of another level,
 * ender pearls whose owner is elsewhere, command blocks, sleeping skipping the night), or
 * made thread safe (the server-wide saved-data store: maps, raids index). A chunk lookup into
 * another level is answered by that level's worker; the asking worker keeps running its own
 * level's queue while it waits, so two workers asking each other cannot deadlock.
 */
public final class ParallelLevelTicker {
    private static volatile boolean active;
    private static volatile boolean timeHoisted;
    private static final ThreadLocal<List<ServerLevel>> OWNED = new ThreadLocal<>();
    private static final Map<ServerLevel, Long> RECENT_NANOS = new IdentityHashMap<>();
    private static final ConcurrentLinkedQueue<Runnable> DEFERRED = new ConcurrentLinkedQueue<>();
    private static final List<ServerLevel> PENDING = new ArrayList<>();
    private static final Set<String> LOGGED_KINDS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static ExecutorService pool;
    private static int poolSize;

    private ParallelLevelTicker() {}

    /** True while the levels tick in parallel and the caller is one of the level workers. */
    public static boolean onWorker() {
        return active && OWNED.get() != null;
    }

    /** True on a level worker when {@code level} belongs to another worker. */
    public static boolean isForeign(Level level) {
        if (!active) return false;
        List<ServerLevel> own = OWNED.get();
        return own != null && !own.contains(level);
    }

    /** ServerLevel.tick asks whether the overworld clock already moved this tick. */
    public static boolean timeHoisted() {
        return timeHoisted;
    }

    /** Queue {@code task} for the server thread right after the parallel phase. */
    public static void defer(String kind, Runnable task) {
        MtmcStats.deferred(kind);
        DEFERRED.add(task);
    }

    /** Called instead of {@code level.tick}: false = tick it now, the vanilla way. */
    public static boolean collect(ServerLevel level) {
        if (!Mtmc.config().parallelDimensions) return false;
        PENDING.add(level);
        return true;
    }

    /** Worker threads for {@code levels} levels with the current config. */
    public static int effectiveThreads(int levels) {
        int t = Mtmc.config().threads;
        return t <= 0 ? levels : Math.min(t, levels);
    }

    /** Called where vanilla's level loop has finished: tick the collected levels together. */
    public static void runCollected(MinecraftServer server, BooleanSupplier haveTime) {
        if (PENDING.isEmpty()) return;
        List<ServerLevel> levels = new ArrayList<>(PENDING);
        PENDING.clear();
        long start = System.nanoTime();
        int threads = effectiveThreads(levels.size());
        if (threads < 2) {
            long[] nanos = new long[levels.size()];
            for (int i = 0; i < levels.size(); i++) {
                long t0 = System.nanoTime();
                tickSerial(levels.get(i), haveTime);
                nanos[i] = System.nanoTime() - t0;
            }
            record(levels, nanos);
            MtmcStats.phase(start, System.nanoTime(), levels, nanos, 1);
            drainDeferred();
            return;
        }

        // The other dimensions read their game time from the overworld's level data. Move the
        // clock (and run /schedule'd functions, which may touch any level) before the phase so
        // every level sees the same time for the whole tick.
        if (server.tickRateManager().runsNormally()) {
            for (ServerLevel level : levels) ((ServerLevelInvoker) level).mtmc$tickTime();
        }
        timeHoisted = true;

        List<List<ServerLevel>> groups = assign(levels, threads);
        ExecutorService exec = pool(groups.size());
        Thread serverThread = Thread.currentThread();
        CountDownLatch ticking = new CountDownLatch(groups.size());
        long[] nanos = new long[levels.size()];
        List<Future<?>> futures = new ArrayList<>(groups.size());
        active = true;
        try {
            for (List<ServerLevel> group : groups) {
                futures.add(exec.submit(() -> tickOnWorker(group, levels, haveTime, ticking, serverThread, nanos)));
            }
            Throwable failure = null;
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    if (failure == null) failure = e.getCause();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (failure == null) failure = e;
                }
            }
            if (failure instanceof ReportedException re) throw re;
            if (failure != null) throw new ReportedException(CrashReport.forThrowable(failure, "Exception ticking world (parallel)"));
        } finally {
            active = false;
            timeHoisted = false;
        }
        record(levels, nanos);
        if (selfTestTicks > 0 && --selfTestTicks == 0) Mtmc.LOGGER.info("selftest done: {}", selfTestResult());
        MtmcStats.phase(start, System.nanoTime(), levels, nanos, groups.size());
        drainDeferred();
    }

    /**
     * Split the levels over {@code threads} workers, longest recent tick first onto the least
     * loaded worker, so with fewer threads than levels the busiest level gets one to itself.
     */
    static List<List<ServerLevel>> assign(List<ServerLevel> levels, int threads) {
        List<List<ServerLevel>> groups = new ArrayList<>();
        long[] load = new long[threads];
        for (int i = 0; i < threads; i++) groups.add(new ArrayList<>());
        List<ServerLevel> byCost = new ArrayList<>(levels);
        byCost.sort((a, b) -> Long.compare(RECENT_NANOS.getOrDefault(b, 0L), RECENT_NANOS.getOrDefault(a, 0L)));
        for (ServerLevel level : byCost) {
            int best = 0;
            for (int i = 1; i < threads; i++) if (load[i] < load[best]) best = i;
            groups.get(best).add(level);
            load[best] += Math.max(1L, RECENT_NANOS.getOrDefault(level, 0L));
        }
        groups.removeIf(List::isEmpty);
        return groups;
    }

    /** Smoothed tick time per level (server thread only), for assign(). */
    private static void record(List<ServerLevel> levels, long[] nanos) {
        for (int i = 0; i < levels.size(); i++) {
            long prev = RECENT_NANOS.getOrDefault(levels.get(i), nanos[i]);
            RECENT_NANOS.put(levels.get(i), (prev * 7 + nanos[i]) / 8);
        }
    }

    private static void tickSerial(ServerLevel level, BooleanSupplier haveTime) {
        try {
            level.tick(haveTime);
        } catch (Throwable t) {
            CrashReport report = CrashReport.forThrowable(t, "Exception ticking world");
            level.fillReportDetails(report);
            throw new ReportedException(report);
        }
    }

    private static void tickOnWorker(List<ServerLevel> group, List<ServerLevel> all, BooleanSupplier haveTime,
                                     CountDownLatch ticking, Thread serverThread, long[] nanos) {
        Thread me = Thread.currentThread();
        for (ServerLevel level : group) claim(level, me);
        OWNED.set(group);
        try {
            for (ServerLevel level : group) {
                long t0 = System.nanoTime();
                try {
                    tickSerial(level, haveTime);
                } finally {
                    nanos[all.indexOf(level)] = System.nanoTime() - t0;
                }
            }
            if (selfTestTicks > 0) crossLevelSelfTest(group, all);
        } finally {
            ticking.countDown();
            try {
                // Keep answering the other workers' chunk requests into these levels until all
                // workers are done ticking.
                while (ticking.getCount() > 0) {
                    if (!pollOwn(group)) LockSupport.parkNanos(20_000L);
                }
            } finally {
                OWNED.remove();
                for (ServerLevel level : group) claim(level, serverThread);
            }
        }
    }

    // ── /mtmc selftest ───────────────────────────────────────────────
    private static volatile int selfTestTicks;
    private static final AtomicInteger SELF_TEST_OK = new AtomicInteger();
    private static final AtomicInteger SELF_TEST_FAIL = new AtomicInteger();

    /** For the next {@code ticks} parallel phases, every worker loads a chunk in every level it doesn't own. */
    public static void startSelfTest(int ticks) {
        SELF_TEST_OK.set(0);
        SELF_TEST_FAIL.set(0);
        selfTestTicks = ticks;
    }

    public static String selfTestResult() {
        return "cross-dimension chunk loads from workers: " + SELF_TEST_OK.get() + " ok, " + SELF_TEST_FAIL.get()
            + " failed" + (selfTestTicks > 0 ? " (" + selfTestTicks + " ticks to go)" : "");
    }

    /**
     * Exercises the cross-level getChunk path (ServerChunkCacheMixin + awaitForeign) on purpose:
     * all workers ask each other at the same time, mid-phase, which is the deadlock case.
     * Vanilla play rarely takes this path, because what crosses dimensions is deferred.
     */
    private static void crossLevelSelfTest(List<ServerLevel> group, List<ServerLevel> all) {
        for (ServerLevel other : all) {
            if (group.contains(other)) continue;
            try {
                ChunkAccess chunk = other.getChunkSource().getChunk(0, 0, ChunkStatus.FULL, true);
                if (chunk != null) SELF_TEST_OK.incrementAndGet(); else SELF_TEST_FAIL.incrementAndGet();
            } catch (Throwable t) {
                SELF_TEST_FAIL.incrementAndGet();
                Mtmc.LOGGER.error("selftest: cross-dimension getChunk into {} failed", other.dimension().identifier(), t);
            }
        }
    }

    private static boolean pollOwn(List<ServerLevel> group) {
        boolean any = false;
        for (ServerLevel level : group) any |= level.getChunkSource().pollTask();
        return any;
    }

    private static void claim(ServerLevel level, Thread owner) {
        ((LevelAccessor) level).mtmc$setThread(owner);
        ((ServerChunkCacheAccessor) level.getChunkSource()).mtmc$setMainThread(owner);
    }

    /**
     * A worker is about to block on another level's chunk queue: run its own level's queue
     * while waiting so a worker waiting on it in turn can't deadlock.
     */
    public static void awaitForeign(CompletableFuture<?> future, ServerChunkCache target) {
        List<ServerLevel> own = active ? OWNED.get() : null;
        if (own == null || own.contains(target.getLevel())) return;
        crossLevel("getChunk " + own.get(0).dimension().identifier() + " -> " + target.getLevel().dimension().identifier());
        while (!future.isDone()) {
            if (!pollOwn(own)) LockSupport.parkNanos(20_000L);
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger DIAG_LOGGED = new java.util.concurrent.atomic.AtomicInteger();

    /** Count something that shouldn't happen; log the details the first 20 times. */
    public static void diagnostic(String kind, java.util.function.Supplier<String> details) {
        MtmcStats.crossLevel("diag:" + kind);
        if (DIAG_LOGGED.incrementAndGet() <= 20) Mtmc.LOGGER.warn("[diag] {}: {}", kind, details.get());
    }

    /** Count (and log once per kind) an access from one level's worker into another level. */
    public static void crossLevel(String kind) {
        MtmcStats.crossLevel(kind);
        if (Mtmc.config().logCrossLevelAccess && LOGGED_KINDS.add(kind)) {
            Mtmc.LOGGER.warn("Cross-dimension access during the parallel phase: {}", kind, new Throwable("stack trace"));
        }
    }

    private static void drainDeferred() {
        Runnable task;
        while ((task = DEFERRED.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                throw new ReportedException(CrashReport.forThrowable(t, "Exception running a deferred cross-dimension task"));
            }
        }
    }

    private static synchronized ExecutorService pool(int threads) {
        if (pool == null || poolSize != threads) {
            if (pool != null) pool.shutdown();
            poolSize = threads;
            pool = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "MTMC Level Thread #" + THREAD_IDS.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
        }
        return pool;
    }
}
