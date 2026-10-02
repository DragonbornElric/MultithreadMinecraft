package dev.mtmc.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheInvoker {
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder mtmc$getVisibleChunkIfPresent(long key);
}
