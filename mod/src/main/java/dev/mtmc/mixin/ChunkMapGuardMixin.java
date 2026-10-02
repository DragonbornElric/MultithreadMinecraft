package dev.mtmc.mixin;

import dev.mtmc.OwnerGuard;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ownership guard on the ChunkMap internals that touch pendingUnloads / updatingChunkMap (see OwnerGuard). */
@Mixin(ChunkMap.class)
abstract class ChunkMapGuardMixin {
    private ServerChunkCache mtmc$cache() {
        return ((ChunkMapAccessor) this).mtmc$level().getChunkSource();
    }

    @Inject(method = "updateChunkScheduling", at = @At("HEAD"), require = 0)
    private void mtmc$guardScheduling(long node, int level, ChunkHolder chunk, int oldLevel, CallbackInfoReturnable<ChunkHolder> cir) {
        ServerChunkCache cache = mtmc$cache();
        if (!OwnerGuard.isOwner(cache)) OwnerGuard.foreign(cache, "updateChunkScheduling");
    }

    @Inject(method = "processUnloads", at = @At("HEAD"), require = 0)
    private void mtmc$guardUnloads(BooleanSupplier haveTime, CallbackInfo ci) {
        ServerChunkCache cache = mtmc$cache();
        if (!OwnerGuard.isOwner(cache)) OwnerGuard.foreign(cache, "processUnloads");
    }

    @Inject(method = "scheduleUnload", at = @At("HEAD"), require = 0)
    private void mtmc$guardScheduleUnload(long pos, ChunkHolder holder, CallbackInfo ci) {
        ServerChunkCache cache = mtmc$cache();
        if (!OwnerGuard.isOwner(cache)) OwnerGuard.foreign(cache, "scheduleUnload");
    }
}
