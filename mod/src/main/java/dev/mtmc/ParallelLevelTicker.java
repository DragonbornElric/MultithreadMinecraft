package dev.mtmc;

import dev.mtmc.mixin.LevelAccessor;
import dev.mtmc.mixin.ServerChunkCacheAccessor;
import dev.mtmc.mixin.ServerLevelInvoker;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Ticks every dimension of the server at the same time, one thread per dimension.
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
    private static final ThreadLocal<ServerLevel> OWNED = new ThreadLocal<>();
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
        ServerLevel own = OWNED.get();
        return own != null && level != own;
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

    /** Called where vanilla's level loop has finished: tick the collected levels together. */
    public static void runCollected(MinecraftServer server, BooleanSupplier haveTime) {
        if (PENDING.isEmpty()) return;
        List<ServerLevel> levels = new ArrayList<>(PENDING);
        PENDING.clear();
        long start = System.nanoTime();
        if (levels.size() == 1) {
            tickSerial(levels.get(0), haveTime);
            MtmcStats.phase(start, System.nanoTime(), levels, new long[] {System.nanoTime() - start});
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

        ExecutorService exec = pool(levels.size());
        Thread serverThread = Thread.currentThread();
        CountDownLatch ticking = new CountDownLatch(levels.size());
        long[] nanos = new long[levels.size()];
        List<Future<?>> futures = new ArrayList<>(levels.size());
        active = true;
        try {
            for (int i = 0; i < levels.size(); i++) {
                ServerLevel level = levels.get(i);
                int idx = i;
                futures.add(exec.submit(() -> tickOnWorker(level, haveTime, ticking, serverThread, nanos, idx)));
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
        MtmcStats.phase(start, System.nanoTime(), levels, nanos);
        drainDeferred();
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

    private static void tickOnWorker(ServerLevel level, BooleanSupplier haveTime, CountDownLatch ticking,
                                     Thread serverThread, long[] nanos, int idx) {
        Thread me = Thread.currentThread();
        claim(level, me);
        OWNED.set(level);
        long t0 = System.nanoTime();
        try {
            tickSerial(level, haveTime);
        } finally {
            nanos[idx] = System.nanoTime() - t0;
            ticking.countDown();
            try {
                // Keep answering the other workers' chunk requests into this level until all
                // levels are done ticking.
                ServerChunkCache cache = level.getChunkSource();
                while (ticking.getCount() > 0) {
                    if (!cache.pollTask()) LockSupport.parkNanos(20_000L);
                }
            } finally {
                OWNED.remove();
                claim(level, serverThread);
            }
        }
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
        ServerLevel own = active ? OWNED.get() : null;
        if (own == null || own.getChunkSource() == target) return;
        crossLevel("getChunk " + own.dimension().identifier() + " -> " + target.getLevel().dimension().identifier());
        ServerChunkCache ownCache = own.getChunkSource();
        while (!future.isDone()) {
            if (!ownCache.pollTask()) LockSupport.parkNanos(20_000L);
        }
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

    private static synchronized ExecutorService pool(int levels) {
        if (pool == null || poolSize < levels) {
            if (pool != null) pool.shutdown();
            poolSize = levels;
            pool = Executors.newFixedThreadPool(levels, r -> {
                Thread t = new Thread(r, "MTMC Level Thread #" + THREAD_IDS.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
        }
        return pool;
    }
}
