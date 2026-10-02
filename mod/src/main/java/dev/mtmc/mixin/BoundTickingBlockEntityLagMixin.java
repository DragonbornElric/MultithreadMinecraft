package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.lag.LagAccess;
import dev.mtmc.lag.LagCategory;
import dev.mtmc.lag.LagTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** One block entity's ticker (hoppers, furnaces, …): its time goes to its chunk. */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
abstract class BoundTickingBlockEntityLagMixin {
    @Shadow
    @Final
    private BlockEntity blockEntity;

    @WrapMethod(method = "tick")
    private void mtmc$timeBlockEntity(Operation<Void> original) {
        Level level = blockEntity.getLevel();
        if (!(level instanceof LagAccess access)) {
            original.call();
            return;
        }
        LagTracker tracker = access.mtmc$lag();
        BlockPos pos = blockEntity.getBlockPos();
        long t = tracker.begin();
        try {
            original.call();
        } finally {
            tracker.end(t, ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), LagCategory.BLOCK_ENTITIES);
        }
    }
}
