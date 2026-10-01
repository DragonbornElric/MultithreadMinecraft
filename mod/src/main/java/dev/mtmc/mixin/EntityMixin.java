package dev.mtmc.mixin;

import dev.mtmc.ParallelLevelTicker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class EntityMixin {
    @Shadow
    public abstract Level level();

    /**
     * Portal handling looks up (and may build) the portal on the other side, in a level another
     * worker is ticking: run it right after the phase. It still runs once per tick.
     */
    @Inject(method = "handlePortal", at = @At("HEAD"), cancellable = true)
    private void mtmc$deferPortal(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.portalProcess != null && ParallelLevelTicker.onWorker()) {
            ci.cancel();
            ParallelLevelTicker.defer("portal", () -> {
                if (!self.isRemoved()) ((EntityInvoker) self).mtmc$handlePortal();
            });
        }
    }

    /** A teleport into or out of a level this worker doesn't own waits for the phase to end. */
    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;",
        at = @At("HEAD"), cancellable = true)
    private void mtmc$deferForeignTeleport(TeleportTransition transition, CallbackInfoReturnable<Entity> cir) {
        if (ParallelLevelTicker.isForeign(transition.newLevel()) || ParallelLevelTicker.isForeign(level())) {
            Entity self = (Entity) (Object) this;
            ParallelLevelTicker.defer("teleport", () -> self.teleport(transition));
            cir.setReturnValue(null);
        }
    }
}
