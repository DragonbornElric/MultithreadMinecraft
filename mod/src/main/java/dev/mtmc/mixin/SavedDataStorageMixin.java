package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The server-wide store (maps, map index, raids index, random sequences) is a plain HashMap
 * that any dimension can reach, e.g. a held map updating in the Nether and one in the
 * Overworld at the same time.
 */
@Mixin(SavedDataStorage.class)
abstract class SavedDataStorageMixin {
    @WrapMethod(method = "computeIfAbsent")
    private <T extends SavedData> T mtmc$computeIfAbsent(SavedDataType<T> type, Operation<T> original) {
        synchronized (this) {
            return original.call(type);
        }
    }

    @WrapMethod(method = "get")
    private <T extends SavedData> T mtmc$get(SavedDataType<T> type, Operation<T> original) {
        synchronized (this) {
            return original.call(type);
        }
    }

    @WrapMethod(method = "set")
    private <T extends SavedData> void mtmc$set(SavedDataType<T> type, T data, Operation<Void> original) {
        synchronized (this) {
            original.call(type, data);
        }
    }
}
