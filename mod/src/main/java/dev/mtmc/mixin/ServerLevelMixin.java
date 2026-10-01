package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mtmc.ParallelLevelTicker;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ClockTimeMarker;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    /** The parallel phase moved the clock already, on the server thread. */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tickTime()V"))
    private void mtmc$skipHoistedTime(ServerLevel level, Operation<Void> original) {
        if (!ParallelLevelTicker.timeHoisted()) original.call(level);
    }

    /** Everyone asleep: the clock is server-wide, so skip the night after the phase. */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/clock/ServerClockManager;moveToTimeMarker(Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceKey;)Z"))
    private boolean mtmc$deferWakeUp(ServerClockManager clocks, Holder<WorldClock> clock, ResourceKey<ClockTimeMarker> marker,
                                     Operation<Boolean> original) {
        if (ParallelLevelTicker.onWorker()) {
            ParallelLevelTicker.defer("sleep_clock", () -> original.call(clocks, clock, marker));
            return true;
        }
        return original.call(clocks, clock, marker);
    }
}
