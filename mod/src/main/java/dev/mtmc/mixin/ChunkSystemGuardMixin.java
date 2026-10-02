package dev.mtmc.mixin;

import dev.mtmc.OwnerGuard;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ownership guard on ServerChunkCache's chunk-system entry points (see OwnerGuard). */
@Mixin(ServerChunkCache.class)
abstract class ChunkSystemGuardMixin {
    @Inject(method = "addTicket", at = @At("HEAD"), cancellable = true, require = 0)
    private void mtmc$guardAddTicket(Ticket ticket, ChunkPos pos, CallbackInfo ci) {
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        if (!OwnerGuard.ownerOrRedispatch(self, "addTicket", () -> self.addTicket(ticket, pos))) ci.cancel();
    }

    @Inject(method = "addTicketWithRadius", at = @At("HEAD"), cancellable = true, require = 0)
    private void mtmc$guardAddTicketWithRadius(TicketType type, ChunkPos pos, int radius, CallbackInfo ci) {
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        if (!OwnerGuard.ownerOrRedispatch(self, "addTicketWithRadius", () -> self.addTicketWithRadius(type, pos, radius))) ci.cancel();
    }

    @Inject(method = "removeTicketWithRadius", at = @At("HEAD"), cancellable = true, require = 0)
    private void mtmc$guardRemoveTicketWithRadius(TicketType type, ChunkPos pos, int radius, CallbackInfo ci) {
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        if (!OwnerGuard.ownerOrRedispatch(self, "removeTicketWithRadius", () -> self.removeTicketWithRadius(type, pos, radius))) ci.cancel();
    }

    @Inject(method = "runDistanceManagerUpdates", at = @At("HEAD"), require = 0)
    private void mtmc$guardDistanceUpdates(CallbackInfoReturnable<Boolean> cir) {
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        if (!OwnerGuard.isOwner(self)) OwnerGuard.foreign(self, "runDistanceManagerUpdates");
    }

    @Inject(method = "save", at = @At("HEAD"), require = 0)
    private void mtmc$guardSave(boolean flush, CallbackInfo ci) {
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        if (!OwnerGuard.isOwner(self)) OwnerGuard.foreign(self, "save");
    }

    @Inject(method = "updateChunkForced", at = @At("HEAD"), require = 0)
    private void mtmc$guardForced(ChunkPos pos, boolean forced, CallbackInfoReturnable<Boolean> cir) {
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        if (!OwnerGuard.isOwner(self)) OwnerGuard.foreign(self, "updateChunkForced");
    }
}
