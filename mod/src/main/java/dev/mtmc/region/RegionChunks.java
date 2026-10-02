package dev.mtmc.region;

import dev.mtmc.mixin.LevelAccessor;
import dev.mtmc.mixin.ServerChunkCacheAccessor;
import dev.mtmc.mixin.ServerChunkCacheInvoker;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Chunk lookups from region threads. The cache's 4-entry "last chunk" arrays belong to the owner
 * thread (several threads writing them could hand back the wrong chunk), so a region thread reads
 * the chunk holder directly. The visible-holder map is replaced whole, never edited, so reading it
 * is safe. A chunk that isn't there yet is loaded the vanilla way inside an exclusive section.
 */
public final class RegionChunks {
    private RegionChunks() {}

    public static ChunkAccess loaded(ServerChunkCache cache, int x, int z, ChunkStatus status) {
        ChunkHolder holder = ((ServerChunkCacheInvoker) cache).mtmc$getVisibleChunkIfPresent(ChunkPos.pack(x, z));
        return holder == null ? null : holder.getChunkIfPresent(status);
    }

    public static ChunkAccess getChunk(RegionPhase phase, ServerChunkCache cache, int x, int z, ChunkStatus status, boolean load) {
        ChunkAccess chunk = loaded(cache, x, z, status);
        if (chunk != null || !load) return chunk;
        if (cache.getLevel() == phase.level) {
            return phase.exclusive("chunk_load", () -> cache.getChunk(x, z, status, true));
        }
        // another level, from a region thread (rare: portals and teleports are deferred)
        return phase.exclusive("cross_level_chunk", () -> {
            ServerChunkCacheAccessor acc = (ServerChunkCacheAccessor) cache;
            Thread owner = acc.mtmc$getMainThread();
            if (owner != phase.ownerThread) {
                // owned by another dimension worker: it answers its queue while it ticks or waits
                return cache.getChunkFuture(x, z, status, true).join().orElse(null);
            }
            // owned by this phase's level thread, which is blocked waiting for the phase: borrow it
            LevelAccessor level = (LevelAccessor) cache.getLevel();
            Thread prevLevelThread = level.mtmc$getThread();
            Thread me = Thread.currentThread();
            acc.mtmc$setMainThread(me);
            level.mtmc$setThread(me);
            try {
                return cache.getChunk(x, z, status, true);
            } finally {
                acc.mtmc$setMainThread(owner);
                level.mtmc$setThread(prevLevelThread);
            }
        });
    }
}
