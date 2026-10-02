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
                if (self.isRemoved()) return;
                boolean wasOnCooldown = self.isOnPortalCooldown();
                var before = self.level();
                ((EntityInvoker) self).mtmc$handlePortal();
                // decided to teleport (cooldown just set) but still here: count it, it shouldn't happen
                if (!wasOnCooldown && self.isOnPortalCooldown() && !self.isRemoved() && self.level() == before) {
                    ParallelLevelTicker.diagnostic("portal_no_teleport", () -> self + " portalProcess=" + self.portalProcess
                        + " passenger=" + self.isPassenger() + " alive=" + self.isAlive() + " pos=" + self.position());
                }
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
