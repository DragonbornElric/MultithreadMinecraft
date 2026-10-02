package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.region.RegionPhase;
import java.util.function.Predicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Advancement triggers edit the player's listener sets and progress. A projectile in one region
 * can trigger them for its owner in another, while that region's thread triggers others: exclusive.
 */
@Mixin(SimpleCriterionTrigger.class)
abstract class RegionAdvancementMixin<T extends SimpleCriterionTrigger.SimpleInstance> {
    @SuppressWarnings("unchecked")
    @WrapMethod(method = "trigger")
    private void mtmc$trigger(ServerPlayer player, Predicate<T> matcher, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(player, matcher);
            return;
        }
        var listeners = player.getAdvancements().getTriggerMapForType((SimpleCriterionTrigger<T>) (Object) this);
        if (listeners == null || listeners.isEmpty()) {
            original.call(player, matcher); // nothing listening: reads only
            return;
        }
        RegionPhase.current().exclusive("advancement", () -> original.call(player, matcher));
    }
}
