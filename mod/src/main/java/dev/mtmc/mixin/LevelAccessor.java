package dev.mtmc.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code Level.thread} is the only thread {@code getBlockEntity} answers on (others get null). */
@Mixin(Level.class)
public interface LevelAccessor {
    @Mutable
    @Accessor("thread")
    void mtmc$setThread(Thread thread);
}
