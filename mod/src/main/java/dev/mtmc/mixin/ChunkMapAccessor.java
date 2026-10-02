package dev.mtmc.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("mainThreadExecutor")
    BlockableEventLoop<Runnable> mtmc$mainThreadExecutor();

    @Accessor("level")
    ServerLevel mtmc$level();
}
