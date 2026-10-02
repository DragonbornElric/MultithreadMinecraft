package dev.mtmc.mixin;

import dev.mtmc.region.RegionPhase;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The locator bar's level-wide connection table: updates from region threads apply after the phase. */
@Mixin(ServerWaypointManager.class)
abstract class RegionWaypointMixin {
    @Inject(method = "updateWaypoint", at = @At("HEAD"), cancellable = true)
    private void mtmc$deferWaypoint(WaypointTransmitter waypoint, CallbackInfo ci) {
        RegionPhase phase = RegionPhase.current();
        if (phase == null) return;
        ci.cancel();
        ServerWaypointManager self = (ServerWaypointManager) (Object) this;
        phase.defer("waypoint", () -> self.updateWaypoint(waypoint));
    }

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void mtmc$deferPlayer(ServerPlayer player, CallbackInfo ci) {
        RegionPhase phase = RegionPhase.current();
        if (phase == null) return;
        ci.cancel();
        ServerWaypointManager self = (ServerWaypointManager) (Object) this;
        phase.defer("waypoint", () -> self.updatePlayer(player));
    }

    @Inject(method = "trackWaypoint", at = @At("HEAD"), cancellable = true)
    private void mtmc$deferTrack(WaypointTransmitter waypoint, CallbackInfo ci) {
        RegionPhase phase = RegionPhase.current();
        if (phase == null) return;
        ci.cancel();
        ServerWaypointManager self = (ServerWaypointManager) (Object) this;
        phase.defer("waypoint", () -> self.trackWaypoint(waypoint));
    }

    @Inject(method = "untrackWaypoint", at = @At("HEAD"), cancellable = true)
    private void mtmc$deferUntrack(WaypointTransmitter waypoint, CallbackInfo ci) {
        RegionPhase phase = RegionPhase.current();
        if (phase == null) return;
        ci.cancel();
        ServerWaypointManager self = (ServerWaypointManager) (Object) this;
        phase.defer("waypoint", () -> self.untrackWaypoint(waypoint));
    }
}
