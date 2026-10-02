package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.region.RegionPhase;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * An entity crossing into another 16-block section moves between section lists, may create or
 * drop a section in the level-wide section map, and may start or stop ticking/tracking. On a
 * region thread that waits for the end of the phase (it then re-reads the entity's position), so
 * the section storage stays read-only while regions run.
 */
@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
abstract class RegionEntitySectionCallbackMixin {
    @Shadow
    @Final
    private EntityAccess entity;

    @Shadow
    private long currentSectionKey;

    @WrapMethod(method = "onMove")
    private void mtmc$deferSectionChange(Operation<Void> original) {
        RegionPhase phase = RegionPhase.current();
        if (phase == null || SectionPos.asLong(entity.blockPosition()) == currentSectionKey) {
            original.call();
            return;
        }
        EntityInLevelCallback self = (EntityInLevelCallback) this;
        EntityAccess e = entity;
        phase.defer("section_move", () -> {
            // removed meanwhile (callback reset): nothing to move
            if (e instanceof Entity entity && ((EntityLevelCallbackAccessor) entity).mtmc$levelCallback() == self) self.onMove();
        });
    }
}
