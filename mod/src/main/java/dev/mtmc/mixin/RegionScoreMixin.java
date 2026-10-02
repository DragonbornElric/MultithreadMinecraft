package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.region.RegionPhase;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/** Kill scores write the server-wide scoreboard (and the killer can be a player in another region). */
@Mixin(Entity.class)
abstract class RegionScoreMixin {
    @WrapMethod(method = "awardKillScore")
    private void mtmc$killScore(Entity victim, DamageSource source, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(victim, source);
            return;
        }
        RegionPhase.current().exclusive("kill_score", () -> original.call(victim, source));
    }
}
