package dev.mtmc;

import dev.mtmc.mixin.ChunkMapAccessor;
import dev.mtmc.mixin.ServerChunkCacheAccessor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerChunkCache;

/**
 * A level's chunk system (ChunkMap, DistanceManager, tickets, the unload queue) belongs to one
 * thread at a time: the server thread, or the level's worker during the parallel phase
 * (ServerChunkCache.mainThread). Vanilla doesn't check this and its maps are not thread safe:
 * two threads in them at once corrupt a fastutil map, and the next lookup can spin forever
 * (the owner's 60 s watchdog in ChunkMap.processUnloads, 2026-10-02).
 *
 * <p>The guard runs at the entry points and, when the caller is not the owner:
 * <ul>
 *   <li>ticket changes (add/remove ticket): handed to the owner's task queue, so they apply a
 *       moment later instead of racing (a ticket is only read at the next distance-manager
 *       update anyway);
 *   <li>everything else (distance-manager updates, the unload queue, chunk scheduling, saves):
 *       counted as {@code diag:foreign_chunk_access:<site>} and logged with the caller's stack
 *       trace, so the code doing it can be found.
 * </ul>
 */
public final class OwnerGuard {
    private static final Map<String, AtomicInteger> LOGGED = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> REDISPATCHED = ThreadLocal.withInitial(() -> false);

    private OwnerGuard() {}

    public static boolean isOwner(ServerChunkCache cache) {
        return Thread.currentThread() == ((ServerChunkCacheAccessor) cache).mtmc$getMainThread();
    }

    /** Not the owner: count it and log the first few stack traces per site. */
    public static void foreign(ServerChunkCache cache, String site) {
        String kind = "foreign_chunk_access:" + site;
        MtmcStats.crossLevel("diag:" + kind);
        int n = LOGGED.computeIfAbsent(site, k -> new AtomicInteger()).incrementAndGet();
        if (n <= 5) {
            Mtmc.LOGGER.error("[diag] {} on thread '{}' (owner '{}', {}, parallel phase {}): {}", kind, Thread.currentThread().getName(),
                ((ServerChunkCacheAccessor) cache).mtmc$getMainThread().getName(), cache.getLevel().dimension().identifier(),
                ParallelLevelTicker.phaseActive() ? "running" : "not running",
                n == 5 ? "(further ones only counted)" : "", new Throwable("caller"));
        }
    }

    /**
     * For a ticket change: true if the caller may go ahead (it is the owner); otherwise the change
     * is queued for the owner and the caller must skip it.
     */
    public static boolean ownerOrRedispatch(ServerChunkCache cache, String site, Runnable again) {
        if (isOwner(cache) || REDISPATCHED.get()) return true;
        foreign(cache, site);
        ((ChunkMapAccessor) cache.chunkMap).mtmc$mainThreadExecutor().execute(() -> {
            REDISPATCHED.set(true);
            try {
                again.run();
            } finally {
                REDISPATCHED.set(false);
            }
        });
        return false;
    }
}
