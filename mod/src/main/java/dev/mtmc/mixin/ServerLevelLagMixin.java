package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.lag.LagAccess;
import dev.mtmc.lag.LagCategory;
import dev.mtmc.lag.LagTracker;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-chunk tick time (see LagTracker). Whole-method wraps, so a mod that rewrites one of
 * these bodies (as Lithium does for other methods) doesn't break the hook.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelLagMixin implements LagAccess {
    @Unique
    private final LagTracker mtmc$lagTracker = new LagTracker();

    @Override
    public LagTracker mtmc$lag() {
        return mtmc$lagTracker;
    }

    @Unique
    private static long mtmc$chunkOf(BlockPos pos) {
        return ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
    }

    @WrapMethod(method = "tickNonPassenger")
    private void mtmc$timeEntity(Entity entity, Operation<Void> original) {
        long chunk = entity.chunkPosition().pack();
        long t = mtmc$lagTracker.begin();
        try {
            original.call(entity);
        } finally {
            mtmc$lagTracker.end(t, chunk, LagCategory.ENTITIES);
        }
    }

    @WrapMethod(method = "tickBlock")
    private void mtmc$timeBlockTick(BlockPos pos, Block type, Operation<Void> original) {
        long t = mtmc$lagTracker.begin();
        try {
            original.call(pos, type);
        } finally {
            mtmc$lagTracker.end(t, mtmc$chunkOf(pos), LagCategory.BLOCK_TICKS);
        }
    }

    @WrapMethod(method = "tickFluid")
    private void mtmc$timeFluidTick(BlockPos pos, Fluid type, Operation<Void> original) {
        long t = mtmc$lagTracker.begin();
        try {
            original.call(pos, type);
        } finally {
            mtmc$lagTracker.end(t, mtmc$chunkOf(pos), LagCategory.FLUID_TICKS);
        }
    }

    @WrapMethod(method = "doBlockEvent")
    private boolean mtmc$timeBlockEvent(BlockEventData event, Operation<Boolean> original) {
        long t = mtmc$lagTracker.begin();
        try {
            return original.call(event);
        } finally {
            mtmc$lagTracker.end(t, mtmc$chunkOf(event.pos()), LagCategory.BLOCK_EVENTS);
        }
    }

    @WrapMethod(method = "tickChunk")
    private void mtmc$timeChunkTick(LevelChunk chunk, int tickSpeed, Operation<Void> original) {
        long t = mtmc$lagTracker.begin();
        try {
            original.call(chunk, tickSpeed);
        } finally {
            mtmc$lagTracker.end(t, chunk.getPos().pack(), LagCategory.CHUNK_TICK);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void mtmc$endLagTick(BooleanSupplier haveTime, CallbackInfo ci) {
        mtmc$lagTracker.endTick();
    }
}
