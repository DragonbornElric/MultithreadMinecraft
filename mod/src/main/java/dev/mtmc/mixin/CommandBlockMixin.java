package dev.mtmc.mixin;

import dev.mtmc.Mtmc;
import dev.mtmc.ParallelLevelTicker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A command can name any dimension (/execute in, /tp, /setblock): run command blocks, with
 * their chains and comparator output, after the phase, still within the same tick.
 */
@Mixin(CommandBlock.class)
abstract class CommandBlockMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void mtmc$deferCommandBlock(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (Mtmc.config().deferCommandBlocks && ParallelLevelTicker.onWorker()) {
            ci.cancel();
            ParallelLevelTicker.defer("command_block", () -> {
                BlockState now = level.getBlockState(pos);
                if (now.getBlock() instanceof CommandBlock) now.tick(level, pos, random);
            });
        }
    }
}
