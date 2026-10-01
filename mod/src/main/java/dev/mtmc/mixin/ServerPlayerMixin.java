package dev.mtmc.mixin;

import dev.mtmc.ParallelLevelTicker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ServerPlayer overrides teleport without calling Entity's for a dimension change. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
        at = @At("HEAD"), cancellable = true)
    private void mtmc$deferForeignTeleport(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (ParallelLevelTicker.isForeign(transition.newLevel()) || ParallelLevelTicker.isForeign(self.level())) {
            ParallelLevelTicker.defer("teleport_player", () -> self.teleport(transition));
            cir.setReturnValue(null);
        }
    }
}
