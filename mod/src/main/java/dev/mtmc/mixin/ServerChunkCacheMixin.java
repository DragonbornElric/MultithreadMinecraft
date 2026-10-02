package dev.mtmc.mixin;

import dev.mtmc.ParallelLevelTicker;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.chunk.LevelChunk;

@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheMixin {
    /**
     * A level worker asking another level's cache for a chunk. Vanilla would hand the load to
     * the owner's queue and block in join(); here the asking worker keeps running its own
     * levels' queues while it waits (see awaitForeign), so two workers asking each other can't
     * deadlock.
     *
     * <p>Hooked at HEAD and routed through the public getChunkFuture, not at the join inside
     * getChunk: Lithium (world.chunk_access) and Moonrise replace getChunk's body, and an
     * INVOKE target inside it then doesn't exist. Lithium keeps the same ownership check on the
     * same mainThread field, so the hand-off below is what its getChunk would do off-thread.
     */
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"), cancellable = true)
    private void mtmc$helpWhileWaiting(int x, int z, ChunkStatus status, boolean load, CallbackInfoReturnable<ChunkAccess> cir) {
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        if (dev.mtmc.ai.SensorPhase.onSensorThread(self)) {
            // parallel sensor phase: read-only lookup of loaded chunks (SensorPhase)
            cir.setReturnValue(dev.mtmc.ai.SensorPhase.readOnlyChunk(self, x, z, status, load));
            return;
        }
        if (!ParallelLevelTicker.onWorker() || Thread.currentThread() == ((ServerChunkCacheAccessor) self).mtmc$getMainThread()) return;
        CompletableFuture<ChunkResult<ChunkAccess>> future = self.getChunkFuture(x, z, status, load);
        ParallelLevelTicker.awaitForeign(future, self);
        ChunkAccess chunk = future.join().orElse(null);
        if (chunk == null && load) {
            throw new IllegalStateException("Chunk not there when requested (cross-dimension): " + x + ", " + z + " " + status);
        }
        cir.setReturnValue(chunk);
    }

    /** getChunkNow answers null off the owner thread; sensor threads get the loaded chunk (read-only). */
    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void mtmc$sensorChunkNow(int x, int z, CallbackInfoReturnable<LevelChunk> cir) {
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        if (dev.mtmc.ai.SensorPhase.onSensorThread(self)) {
            ChunkAccess c = dev.mtmc.ai.SensorPhase.readOnlyChunk(self, x, z, ChunkStatus.FULL, false);
            cir.setReturnValue(c instanceof LevelChunk lc ? lc : null);
        }
    }
}
