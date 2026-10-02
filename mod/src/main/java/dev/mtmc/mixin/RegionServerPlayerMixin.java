package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.region.RegionPhase;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Players on region threads: teleports after the phase; stats write the server-wide scoreboard, so exclusive. */
@Mixin(ServerPlayer.class)
abstract class RegionServerPlayerMixin {
    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
        at = @At("HEAD"), cancellable = true)
    private void mtmc$regionDeferTeleport(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> cir) {
        RegionPhase phase = RegionPhase.current();
        if (phase != null) {
            ServerPlayer self = (ServerPlayer) (Object) this;
            phase.defer("teleport_player", () -> self.teleport(transition));
            cir.setReturnValue(null);
        }
    }

    @WrapMethod(method = "awardStat(Lnet/minecraft/stats/Stat;I)V")
    private void mtmc$awardStatExclusive(Stat<?> stat, int count, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(stat, count);
            return;
        }
        RegionPhase.current().exclusive("stat", () -> original.call(stat, count));
    }

    @WrapMethod(method = "resetStat")
    private void mtmc$resetStatExclusive(Stat<?> stat, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(stat);
            return;
        }
        RegionPhase.current().exclusive("stat", () -> original.call(stat));
    }
}
