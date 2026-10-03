package dev.mtmc.mixin;

import dev.mtmc.region.SyncLong2ReferenceMap;
import dev.mtmc.region.SyncReferenceSet;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lithium compatibility for region ticking: Lithium keeps per-level collections that a mob writes
 * to from inside its own tick, so two region threads at once corrupt them. Each is replaced with a
 * synchronized one when the level's data is built.
 * <ul>
 * <li>{@code activeNavigations} ({@code entity.inactive_navigations}): a mob adds or removes itself
 * when it starts or stops a path (an NPE in sendBlockUpdated later).</li>
 * <li>{@code chunkSectionChangeCallbacks} ({@code util.block_tracking}): a callback is put in the
 * first time a block-change tracker watches a chunk section, which experimental
 * {@code entity.block_caching} does from entity ticks (ArrayIndexOutOfBoundsException on resize,
 * "Ticking entity" crash).</li>
 * </ul>
 * The two tracker interners are covered by {@link RegionLithiumInternerMixin}.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.lithium.common.world.LithiumData$Data")
abstract class RegionLithiumDataMixin {
    @Redirect(method = "<init>(Lnet/minecraft/core/HolderLookup$Provider;)V",
        at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/objects/ReferenceOpenHashSet;"))
    private static ReferenceOpenHashSet<?> mtmc$syncActiveNavigations() {
        return new SyncReferenceSet<>();
    }

    @Redirect(method = "<init>(Lnet/minecraft/core/HolderLookup$Provider;)V",
        at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/longs/Long2ReferenceOpenHashMap;"))
    private static Long2ReferenceOpenHashMap<?> mtmc$syncSectionCallbacks() {
        return new SyncLong2ReferenceMap<>();
    }
}
