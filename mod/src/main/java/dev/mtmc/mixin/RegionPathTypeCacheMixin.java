package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.region.RegionPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The level's path-type cache is two plain arrays written on lookup; two threads writing a slot
 * could pair a position with another position's type. Region threads compute without it.
 */
@Mixin(PathTypeCache.class)
abstract class RegionPathTypeCacheMixin {
    @WrapMethod(method = "getOrCompute")
    private PathType mtmc$noSharedCache(BlockGetter level, BlockPos pos, Operation<PathType> original) {
        if (RegionPhase.current() == null) return original.call(level, pos);
        return WalkNodeEvaluatorInvoker.mtmc$getPathTypeFromState(level, pos);
    }
}
