package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.lag.EntityCaps;
import dev.mtmc.lag.LagAccess;
import dev.mtmc.lag.LagCategory;
import dev.mtmc.lag.LagTracker;
import dev.mtmc.lag.ThrottleNotices;
import net.minecraft.world.entity.player.Player;
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

    @Unique
    private boolean mtmc$hooked;

    /** Throttle phase follows game time; hook up notifications on the first tick. */
    @Inject(method = "tick", at = @At("HEAD"))
    private void mtmc$startLagTick(BooleanSupplier haveTime, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (!mtmc$hooked) {
            mtmc$hooked = true;
            mtmc$lagTracker.hooks(change -> ThrottleNotices.changed(self, change), () -> self.getServer().getCurrentSmoothedTickTime());
        }
        mtmc$lagTracker.startTick(self.getGameTime());
        EntityCaps.release(self);
    }

    /** Per-chunk caps for new entities (EntityCaps). */
    @WrapMethod(method = "addFreshEntity")
    private boolean mtmc$capFreshEntity(Entity entity, Operation<Boolean> original) {
        Boolean decided = EntityCaps.onAdd((ServerLevel) (Object) this, entity);
        return decided != null ? decided : original.call(entity);
    }

    /** Entities waiting under a cap go into the world before every save. */
    @Inject(method = "save", at = @At("HEAD"))
    private void mtmc$flushDelayedEntities(net.minecraft.util.ProgressListener listener, boolean flush, boolean noSave, CallbackInfo ci) {
        EntityCaps.flushAll((ServerLevel) (Object) this);
    }

    /** Throttle: a slowed chunk is "not ticking" on its off ticks, as outside simulation distance. */
    @WrapMethod(method = "shouldTickBlocksAt(J)Z")
    private boolean mtmc$throttleBlockTicking(long chunkPos, Operation<Boolean> original) {
        return original.call(chunkPos) && !mtmc$lagTracker.skipNow(chunkPos);
    }

    /** The check scheduled block and fluid ticks use (LevelTicks): skipped containers keep their ticks queued, in order. */
    @WrapMethod(method = "isPositionTickingWithEntitiesLoaded")
    private boolean mtmc$throttleScheduledTicks(long chunkPos, Operation<Boolean> original) {
        return original.call(chunkPos) && !mtmc$lagTracker.skipNow(chunkPos);
    }

    @WrapMethod(method = "tickNonPassenger")
    private void mtmc$timeEntity(Entity entity, Operation<Void> original) {
        long chunk = entity.chunkPosition().pack();
        if (mtmc$lagTracker.skipNow(chunk) && !(entity instanceof Player) && !entity.hasPassenger(e -> e instanceof Player)) {
            return; // throttled chunk, off tick: the entity waits, as outside simulation distance
        }
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
        if (mtmc$lagTracker.skipNow(chunk.getPos().pack())) return; // throttled: no random ticks this tick
        long t = mtmc$lagTracker.begin();
        try {
            original.call(chunk, tickSpeed);
        } finally {
            mtmc$lagTracker.end(t, chunk.getPos().pack(), LagCategory.CHUNK_TICK);
        }
    }

    /** Parallel sensor phase, right before the entity tick loop (SensorPhase). */
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void mtmc$sensorPhase(BooleanSupplier haveTime, CallbackInfo ci) {
        dev.mtmc.ai.SensorPhase.run((ServerLevel) (Object) this);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void mtmc$endLagTick(BooleanSupplier haveTime, CallbackInfo ci) {
        mtmc$lagTracker.endTick();
    }
}
