package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mtmc.ParallelLevelTicker;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheMixin {
    /**
     * getChunk off the owning thread hands the load to the owner's queue and joins. A level
     * worker asking another level keeps running its own queue meanwhile (see awaitForeign).
     */
    @WrapOperation(method = "getChunk", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;join()Ljava/lang/Object;", ordinal = 0))
    private Object mtmc$helpWhileWaiting(CompletableFuture<?> future, Operation<Object> original) {
        ParallelLevelTicker.awaitForeign(future, (ServerChunkCache) (Object) this);
        return original.call(future);
    }
}
