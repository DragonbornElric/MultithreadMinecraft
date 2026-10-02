package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.region.RegionPhase;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;

/** Scheduled block and fluid ticks: the level-wide queue of containers is shared. */
@Mixin(LevelTicks.class)
abstract class RegionLevelTicksMixin<T> {
    @WrapMethod(method = "schedule")
    private void mtmc$scheduleExclusive(ScheduledTick<T> tick, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(tick);
            return;
        }
        RegionPhase.current().exclusive("schedule_tick", () -> original.call(tick));
    }
}
