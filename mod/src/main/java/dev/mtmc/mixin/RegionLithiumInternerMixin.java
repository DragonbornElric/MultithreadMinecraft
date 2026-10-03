package dev.mtmc.mixin;

import dev.mtmc.region.SyncObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lithium compatibility for region ticking. {@code LithiumInterner} keeps the level-wide canonical
 * block-change and entity-movement trackers. A mob's block cache ({@code entity.block_caching}) and
 * item, inventory and collider lookups register trackers into it and delete them from inside entity
 * ticks, so its set is made a synchronized one.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.lithium.common.util.deduplication.LithiumInterner")
abstract class RegionLithiumInternerMixin {
    @Redirect(method = "<init>()V",
        at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/objects/ObjectOpenHashSet;"))
    private static ObjectOpenHashSet<?> mtmc$syncCanonicalStorage() {
        return new SyncObjectSet<>();
    }
}
