package dev.mtmc.mixin;

import net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DynamicGraphMinFixedPoint.class)
public interface DynamicGraphInvoker {
    @Invoker("hasWork")
    boolean mtmc$hasWork();
}
