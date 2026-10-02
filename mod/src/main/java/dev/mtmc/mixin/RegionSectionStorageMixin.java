package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.region.RegionPhase;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.Optional;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** POI reads can unpack a chunk's data into the shared map on first use: that part is exclusive. */
@Mixin(SectionStorage.class)
abstract class RegionSectionStorageMixin<R> {
    @Shadow
    @Final
    private Long2ObjectMap<Optional<R>> storage;

    @WrapMethod(method = "getOrLoad")
    private Optional<R> mtmc$getOrLoad(long sectionPos, Operation<Optional<R>> original) {
        if (!RegionPhase.needsExclusive()) return original.call(sectionPos);
        Optional<R> present = storage.get(sectionPos);
        if (present != null) return present;
        return RegionPhase.current().exclusive("poi_load", () -> original.call(sectionPos));
    }
}
