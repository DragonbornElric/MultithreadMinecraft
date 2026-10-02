package dev.mtmc.mixin;

import dev.mtmc.region.SyncReferenceSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lithium compatibility for region ticking. Lithium's {@code mixin.entity.inactive_navigations}
 * keeps one set of active path navigations per level, and a mob adds or removes itself whenever it
 * starts or stops a path, from inside its tick: two region threads at once corrupt the set (an NPE
 * in sendBlockUpdated later). The set is made a synchronized one. Lithium's other per-level data
 * (block and entity-movement trackers) is only written by hoppers and section moves, which run on
 * the level's thread.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.lithium.common.world.LithiumData$Data")
abstract class RegionLithiumDataMixin {
    @Redirect(method = "<init>(Lnet/minecraft/core/HolderLookup$Provider;)V",
        at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/objects/ReferenceOpenHashSet;"))
    private static ReferenceOpenHashSet<?> mtmc$syncActiveNavigations() {
        return new SyncReferenceSet<>();
    }
}
