package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mtmc.lag.LagAccess;
import dev.mtmc.lag.LagCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Light is computed off the tick thread, so a light-spam machine barely shows in tick time.
 * Count the light checks a chunk's block changes queue (LagCategory.LIGHT).
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkLightMixin {
    @Shadow
    @Final
    Level level;

    @WrapOperation(method = "setBlockState", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;checkBlock(Lnet/minecraft/core/BlockPos;)V"))
    private void mtmc$countLight(LevelLightEngine engine, BlockPos pos, Operation<Void> original) {
        if (level instanceof LagAccess access) access.mtmc$lag().count(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), LagCategory.LIGHT);
        original.call(engine, pos);
    }
}
