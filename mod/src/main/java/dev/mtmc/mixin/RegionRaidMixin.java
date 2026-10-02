package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.region.RegionPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;

/** A raid's raiders can be spread over several regions: changes to the raid are exclusive. */
@Mixin(Raid.class)
abstract class RegionRaidMixin {
    @WrapMethod(method = "joinRaid")
    private void mtmc$join(ServerLevel level, int group, Raider raider, BlockPos pos, boolean exists, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(level, group, raider, pos, exists);
            return;
        }
        RegionPhase.current().exclusive("raid", () -> original.call(level, group, raider, pos, exists));
    }

    @WrapMethod(method = "removeFromRaid")
    private void mtmc$remove(ServerLevel level, Raider raider, boolean health, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(level, raider, health);
            return;
        }
        RegionPhase.current().exclusive("raid", () -> original.call(level, raider, health));
    }

    @WrapMethod(method = "addWaveMob(Lnet/minecraft/server/level/ServerLevel;ILnet/minecraft/world/entity/raid/Raider;Z)Z")
    private boolean mtmc$addWaveMob(ServerLevel level, int wave, Raider raider, boolean health, Operation<Boolean> original) {
        if (!RegionPhase.needsExclusive()) return original.call(level, wave, raider, health);
        return RegionPhase.current().exclusive("raid", () -> original.call(level, wave, raider, health));
    }

    @WrapMethod(method = "setLeader")
    private void mtmc$setLeader(int wave, Raider raider, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(wave, raider);
            return;
        }
        RegionPhase.current().exclusive("raid", () -> original.call(wave, raider));
    }

    @WrapMethod(method = "removeLeader")
    private void mtmc$removeLeader(int wave, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(wave);
            return;
        }
        RegionPhase.current().exclusive("raid", () -> original.call(wave));
    }

    @WrapMethod(method = "updateBossbar")
    private void mtmc$bossbar(Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call();
            return;
        }
        RegionPhase.current().exclusive("raid", () -> original.call());
    }

    @WrapMethod(method = "addHeroOfTheVillage")
    private void mtmc$hero(net.minecraft.world.entity.Entity killer, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(killer);
            return;
        }
        RegionPhase.current().exclusive("raid", () -> original.call(killer));
    }
}
