package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.region.Deaths;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/** A death (kill score, death events, death loot) runs alone, server-wide: see {@link Deaths}. */
@Mixin(LivingEntity.class)
abstract class DeathMixin {
    @WrapMethod(method = "die")
    private void mtmc$oneDeathAtATime(DamageSource source, Operation<Void> original) {
        if (((LivingEntity) (Object) this).level().isClientSide()) {
            original.call(source);
            return;
        }
        Deaths.run(() -> original.call(source));
    }
}
