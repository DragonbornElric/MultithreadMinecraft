package dev.mtmc.lab.mixin;

import dev.mtmc.lab.LabPeer;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.BooleanSupplier;

@Mixin(value=ServerLevel.class, priority=2000)
public abstract class LabDimensionTickMixin {
    @Inject(method="tick", at=@At("HEAD"), cancellable=true)
    private void onlyAssignedDimension(BooleanSupplier budget, CallbackInfo ci) {
        String dimension = ((ServerLevel)(Object)this).dimension().identifier().toString();
        if (!LabPeer.allowTick(dimension)) ci.cancel();
    }
    @Inject(method="tick", at=@At("RETURN"))
    private void completed(BooleanSupplier budget, CallbackInfo ci) {
        LabPeer.completedTick(((ServerLevel)(Object)this).dimension().identifier().toString());
    }
}
