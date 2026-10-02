package dev.mtmc.mixin;

import net.minecraft.util.RandomSource;
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

    @Accessor("thread")
    Thread mtmc$getThread();

    /** Region threads: the level's shared random becomes per-thread (RegionRandom). */
    @Mutable
    @Accessor("random")
    void mtmc$setRandom(RandomSource random);

    @Accessor("random")
    RandomSource mtmc$getRandom();
}
