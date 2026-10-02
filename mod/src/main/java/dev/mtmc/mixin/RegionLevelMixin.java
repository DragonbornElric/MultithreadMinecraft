package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.region.RegionPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Block changes from region threads take the level exclusively (RegionPhase); block entity reads are allowed on them. */
@Mixin(Level.class)
abstract class RegionLevelMixin {
    /** Lighting, heightmaps, POI, neighbour updates, block entity tickers, the chunk's change set: all shared. */
    @WrapMethod(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean mtmc$setBlockExclusive(BlockPos pos, BlockState state, int flags, int limit, Operation<Boolean> original) {
        if (!RegionPhase.needsExclusive()) return original.call(pos, state, flags, limit);
        return RegionPhase.current().exclusive("set_block", () -> original.call(pos, state, flags, limit));
    }

    /** One turn for the drops, the event and the change instead of three. */
    @WrapMethod(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z")
    private boolean mtmc$destroyBlockExclusive(BlockPos pos, boolean drop, Entity breaker, int limit, Operation<Boolean> original) {
        if (!RegionPhase.needsExclusive()) return original.call(pos, drop, breaker, limit);
        return RegionPhase.current().exclusive("destroy_block", () -> original.call(pos, drop, breaker, limit));
    }

    @WrapMethod(method = "setBlockEntity")
    private void mtmc$setBlockEntityExclusive(BlockEntity be, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(be);
            return;
        }
        RegionPhase.current().exclusive("set_block_entity", () -> original.call(be));
    }

    @WrapMethod(method = "removeBlockEntity")
    private void mtmc$removeBlockEntityExclusive(BlockPos pos, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(pos);
            return;
        }
        RegionPhase.current().exclusive("remove_block_entity", () -> original.call(pos));
    }

    /** getBlockEntity answers only on Level.thread; region threads of this level are owners for reading. */
    @ModifyExpressionValue(method = "getBlockEntity", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
        target = "Lnet/minecraft/world/level/Level;thread:Ljava/lang/Thread;"))
    private Thread mtmc$regionThreadReadsBlockEntities(Thread owner) {
        return RegionPhase.onRegionThread((Level) (Object) this) ? Thread.currentThread() : owner;
    }
}
