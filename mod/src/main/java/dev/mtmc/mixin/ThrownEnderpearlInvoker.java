package dev.mtmc.mixin;

import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ThrownEnderpearl.class)
public interface ThrownEnderpearlInvoker {
    @Invoker("onHit")
    void mtmc$onHit(HitResult hit);
}
