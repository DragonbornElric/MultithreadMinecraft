package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.region.RegionPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

/** getBlockEntity(IMMEDIATE) creates a missing block entity on read: that write is exclusive. */
@Mixin(LevelChunk.class)
abstract class RegionLevelChunkMixin {
    @WrapMethod(method = "createBlockEntity")
    private BlockEntity mtmc$create(BlockPos pos, Operation<BlockEntity> original) {
        if (!RegionPhase.needsExclusive()) return original.call(pos);
        return RegionPhase.current().exclusive("create_block_entity", () -> original.call(pos));
    }

    @WrapMethod(method = "promotePendingBlockEntity")
    private BlockEntity mtmc$promote(BlockPos pos, CompoundTag tag, Operation<BlockEntity> original) {
        if (!RegionPhase.needsExclusive()) return original.call(pos, tag);
        return RegionPhase.current().exclusive("create_block_entity", () -> original.call(pos, tag));
    }
}
