package dev.mtmc.mixin;

import dev.mtmc.ParallelLevelTicker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A pearl whose owner is in another dimension pulls the owner over and hurts them: the whole
 * landing runs after the phase (deferring only the teleport would lose the damage, which
 * vanilla applies to the teleported player).
 */
@Mixin(ThrownEnderpearl.class)
abstract class ThrownEnderpearlMixin {
    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void mtmc$deferCrossDimensionLanding(HitResult hit, CallbackInfo ci) {
        ThrownEnderpearl self = (ThrownEnderpearl) (Object) this;
        Entity owner = self.getOwner();
        if (owner != null && ParallelLevelTicker.isForeign(owner.level())) {
            ci.cancel();
            ParallelLevelTicker.defer("ender_pearl", () -> {
                if (!self.isRemoved()) ((ThrownEnderpearlInvoker) self).mtmc$onHit(hit);
            });
        }
    }
}
