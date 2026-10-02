package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.ai.SensorPhase;
import java.util.Collection;
import net.minecraft.util.ClassInstanceMultiMap;
import org.spongepowered.asm.mixin.Mixin;

/**
 * An entity section's find(Class) isn't read-only: the first lookup of a class fills a cache
 * entry in a plain HashMap (vanilla's computeIfAbsent, Lithium's get-then-put overwrite). While
 * a parallel sensor phase runs, several threads look up entities in the same sections, so the
 * lookup holds this map's lock then (ConcurrentModificationException / a corrupt map otherwise).
 * Wraps whichever find is installed, Lithium's included. Outside the phase: one volatile read.
 */
@Mixin(ClassInstanceMultiMap.class)
public class ClassInstanceMultiMapMixin {
    @WrapMethod(method = "find")
    private Collection<?> mtmc$lockDuringSensorPhase(Class<?> index, Operation<Collection<?>> original) {
        if (SensorPhase.ACTIVE.get() == 0) return original.call(index);
        synchronized (this) {
            return original.call(index);
        }
    }
}
