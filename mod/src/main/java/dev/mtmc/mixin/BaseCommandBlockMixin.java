package dev.mtmc.mixin;

import dev.mtmc.Mtmc;
import dev.mtmc.ParallelLevelTicker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseCommandBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Command block minecarts (block command blocks are deferred whole by CommandBlockMixin). */
@Mixin(BaseCommandBlock.class)
abstract class BaseCommandBlockMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void mtmc$deferCommand(ServerLevel level, CallbackInfoReturnable<Boolean> cir) {
        if (Mtmc.config().deferCommandBlocks && ParallelLevelTicker.onWorker()) {
            BaseCommandBlock self = (BaseCommandBlock) (Object) this;
            ParallelLevelTicker.defer("command_minecart", () -> self.performCommand(level));
            cir.setReturnValue(true);
        }
    }
}
