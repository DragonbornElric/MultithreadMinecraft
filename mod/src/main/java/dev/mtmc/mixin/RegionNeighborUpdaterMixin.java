package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.region.RegionPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The level's neighbour updater is one stack for the whole level. Entities reach it without a
 * block change too (an arrow pressing a button, a mob on a pressure plate): exclusive.
 */
@Mixin(CollectingNeighborUpdater.class)
abstract class RegionNeighborUpdaterMixin {
    @WrapMethod(method = "shapeUpdate")
    private void mtmc$shape(Direction direction, BlockState neighborState, BlockPos pos, BlockPos neighborPos, int flags, int limit,
                            Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(direction, neighborState, pos, neighborPos, flags, limit);
            return;
        }
        RegionPhase.current().exclusive("neighbor_update", () -> original.call(direction, neighborState, pos, neighborPos, flags, limit));
    }

    @WrapMethod(method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V")
    private void mtmc$simple(BlockPos pos, Block block, Orientation orientation, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(pos, block, orientation);
            return;
        }
        RegionPhase.current().exclusive("neighbor_update", () -> original.call(pos, block, orientation));
    }

    @WrapMethod(method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V")
    private void mtmc$full(BlockState state, BlockPos pos, Block block, Orientation orientation, boolean piston, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(state, pos, block, orientation, piston);
            return;
        }
        RegionPhase.current().exclusive("neighbor_update", () -> original.call(state, pos, block, orientation, piston));
    }

    @WrapMethod(method = "updateNeighborsAtExceptFromFacing")
    private void mtmc$multi(BlockPos pos, Block block, Direction skip, Orientation orientation, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(pos, block, skip, orientation);
            return;
        }
        RegionPhase.current().exclusive("neighbor_update", () -> original.call(pos, block, skip, orientation));
    }
}
