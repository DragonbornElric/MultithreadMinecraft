package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mtmc.ParallelLevelTicker;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    /** Vanilla's level loop only collects the levels... */
    @WrapOperation(method = "tickChildren", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void mtmc$collectLevel(ServerLevel level, BooleanSupplier haveTime, Operation<Void> original) {
        if (!ParallelLevelTicker.collect(level)) original.call(level, haveTime);
    }

    /** ...and they tick together where the loop ends, before connections and players. */
    @Inject(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickConnection()V"))
    private void mtmc$tickLevels(BooleanSupplier haveTime, CallbackInfo ci) {
        ParallelLevelTicker.runCollected((MinecraftServer) (Object) this, haveTime);
    }
}
