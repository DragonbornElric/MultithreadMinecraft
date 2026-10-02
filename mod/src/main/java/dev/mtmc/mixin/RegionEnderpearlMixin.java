package dev.mtmc.mixin;

import dev.mtmc.region.RegionPhase;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A pearl landing moves its owner, who can be anywhere: the whole landing runs after the phase. */
@Mixin(ThrownEnderpearl.class)
abstract class RegionEnderpearlMixin {
    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void mtmc$regionDeferLanding(HitResult hit, CallbackInfo ci) {
        RegionPhase phase = RegionPhase.current();
        if (phase == null) return;
        ci.cancel();
        ThrownEnderpearl self = (ThrownEnderpearl) (Object) this;
        phase.defer("ender_pearl", () -> {
            if (!self.isRemoved()) ((ThrownEnderpearlInvoker) self).mtmc$onHit(hit);
        });
    }
}
