package dev.mtmc.mixin;

import dev.mtmc.region.RegionPhase;
import net.minecraft.world.entity.TamableAnimal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A pet jumping to its owner can land in another region: after the phase. */
@Mixin(TamableAnimal.class)
abstract class RegionTamableMixin {
    @Inject(method = "tryToTeleportToOwner", at = @At("HEAD"), cancellable = true)
    private void mtmc$deferPetTeleport(CallbackInfo ci) {
        RegionPhase phase = RegionPhase.current();
        if (phase == null) return;
        ci.cancel();
        TamableAnimal self = (TamableAnimal) (Object) this;
        phase.defer("pet_teleport", () -> {
            if (!self.isRemoved()) self.tryToTeleportToOwner();
        });
    }
}
