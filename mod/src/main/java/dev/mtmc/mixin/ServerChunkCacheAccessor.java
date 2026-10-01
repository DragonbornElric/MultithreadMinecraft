package dev.mtmc.mixin;

import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code ServerChunkCache.mainThread} decides who may load chunks directly and who runs the
 * cache's task queue ({@code MainThreadExecutor.getRunningThread()} returns it).
 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {
    @Mutable
    @Accessor("mainThread")
    void mtmc$setMainThread(Thread thread);

    @Accessor("mainThread")
    Thread mtmc$getMainThread();
}
