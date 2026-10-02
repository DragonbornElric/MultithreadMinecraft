package dev.mtmc.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityLevelCallbackAccessor {
    @Accessor("levelCallback")
    EntityInLevelCallback mtmc$levelCallback();
}
