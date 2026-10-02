package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mtmc.region.RegionPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import org.spongepowered.asm.mixin.Mixin;

/** A raid omen running out starts a raid: the level's raid map is shared. */
@Mixin(Raids.class)
abstract class RegionRaidsMixin {
    @WrapMethod(method = "createOrExtendRaid")
    private Raid mtmc$create(ServerPlayer player, BlockPos pos, Operation<Raid> original) {
        if (!RegionPhase.needsExclusive()) return original.call(player, pos);
        return RegionPhase.current().exclusive("raid", () -> original.call(player, pos));
    }
}
