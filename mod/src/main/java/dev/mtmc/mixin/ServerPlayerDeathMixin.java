package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.region.Deaths;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;

/** A player's death does more before LivingEntity.die (death message, scoreboard, drops): all of it alone. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerDeathMixin {
    @WrapMethod(method = "die")
    private void mtmc$oneDeathAtATime(DamageSource source, Operation<Void> original) {
        Deaths.run(() -> original.call(source));
    }
}
