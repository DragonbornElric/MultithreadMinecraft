package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mtmc.region.RegionPhase;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * POI claims (a villager taking a bed or a job site) and changes mark shared dirty sets: exclusive.
 * So does the village-distance tracker when a read has to run its pending updates first.
 */
@Mixin(PoiManager.class)
abstract class RegionPoiMixin {
    @WrapMethod(method = "take")
    private Optional<BlockPos> mtmc$take(Predicate<Holder<PoiType>> type, BiPredicate<Holder<PoiType>, BlockPos> filter, BlockPos center,
                                        int radius, Operation<Optional<BlockPos>> original) {
        if (!RegionPhase.needsExclusive()) return original.call(type, filter, center, radius);
        return RegionPhase.current().exclusive("poi", () -> original.call(type, filter, center, radius));
    }

    @WrapMethod(method = "release")
    private boolean mtmc$release(BlockPos pos, Operation<Boolean> original) {
        if (!RegionPhase.needsExclusive()) return original.call(pos);
        return RegionPhase.current().exclusive("poi", () -> original.call(pos));
    }

    @WrapMethod(method = "add")
    private PoiRecord mtmc$add(BlockPos pos, Holder<PoiType> type, Operation<PoiRecord> original) {
        if (!RegionPhase.needsExclusive()) return original.call(pos, type);
        return RegionPhase.current().exclusive("poi", () -> original.call(pos, type));
    }

    @WrapMethod(method = "remove")
    private void mtmc$remove(BlockPos pos, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(pos);
            return;
        }
        RegionPhase.current().exclusive("poi", () -> original.call(pos));
    }

    @WrapMethod(method = "ensureLoadedAndValid")
    private void mtmc$ensureLoaded(LevelReader reader, BlockPos center, int radius, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(reader, center, radius);
            return;
        }
        RegionPhase.current().exclusive("poi_load", () -> original.call(reader, center, radius));
    }

    /**
     * isVillage (iron golems, raids, villagers) runs the level-wide village-distance tracker's
     * pending updates before reading it. With nothing pending it only reads.
     */
    @WrapOperation(method = "sectionsToVillage", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager$DistanceTracker;runAllUpdates()V"))
    private void mtmc$villageDistanceExclusive(@Coerce Object tracker, Operation<Void> original) {
        if (!RegionPhase.needsExclusive() || !((DynamicGraphInvoker) tracker).mtmc$hasWork()) {
            original.call(tracker);
            return;
        }
        RegionPhase.current().exclusive("poi_distance", () -> original.call(tracker));
    }
}
