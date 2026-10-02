package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mtmc.region.RegionPhase;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Region threads: an entity's removal from the section/tick/tracking lists waits for the end
 * of the phase (it's marked removed at once, so the loop and alive checks skip it); teleports and
 * portals, which can move an entity into another region or level, run after the phase too.
 */
@Mixin(Entity.class)
abstract class RegionEntityMixin {
    @WrapOperation(method = "setRemoved", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/entity/EntityInLevelCallback;onRemove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"))
    private void mtmc$deferRemoval(EntityInLevelCallback callback, Entity.RemovalReason reason, Operation<Void> original) {
        RegionPhase phase = RegionPhase.current();
        if (phase == null) {
            original.call(callback, reason);
            return;
        }
        phase.defer("remove_entity", () -> original.call(callback, reason));
    }

    @Inject(method = "handlePortal", at = @At("HEAD"), cancellable = true)
    private void mtmc$regionDeferPortal(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        RegionPhase phase = RegionPhase.current();
        if (phase != null && self.portalProcess != null) {
            ci.cancel();
            phase.defer("portal", () -> {
                if (!self.isRemoved()) ((EntityInvoker) self).mtmc$handlePortal();
            });
        }
    }

    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;",
        at = @At("HEAD"), cancellable = true)
    private void mtmc$regionDeferTeleport(TeleportTransition transition, CallbackInfoReturnable<Entity> cir) {
        RegionPhase phase = RegionPhase.current();
        if (phase != null) {
            Entity self = (Entity) (Object) this;
            phase.defer("teleport", () -> self.teleport(transition));
            cir.setReturnValue(null);
        }
    }
}
