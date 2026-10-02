package dev.mtmc.mixin;

import dev.mtmc.region.RegionPhase;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseCommandBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A command (minecart) can touch anything: after the phase. */
@Mixin(BaseCommandBlock.class)
abstract class RegionCommandMinecartMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void mtmc$regionDeferCommand(ServerLevel level, CallbackInfoReturnable<Boolean> cir) {
        RegionPhase phase = RegionPhase.current();
        if (phase == null) return;
        BaseCommandBlock self = (BaseCommandBlock) (Object) this;
        phase.defer("command_minecart", () -> self.performCommand(level));
        cir.setReturnValue(true);
    }
}
