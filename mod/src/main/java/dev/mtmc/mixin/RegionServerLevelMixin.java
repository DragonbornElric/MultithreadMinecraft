package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mtmc.region.RegionPhase;
import dev.mtmc.region.RegionRandom;
import dev.mtmc.region.RegionTicker;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Region ticking (RegionTicker): the entity loop runs as regions, and the ServerLevel writes an
 * entity tick can make to shared level state take the level exclusively (RegionPhase).
 */
@Mixin(ServerLevel.class)
abstract class RegionServerLevelMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void mtmc$regionRandom(CallbackInfo ci) {
        LevelAccessor self = (LevelAccessor) this;
        self.mtmc$setRandom(new RegionRandom(self.mtmc$getRandom()));
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void mtmc$tickEntitiesAsRegions(EntityTickList list, Consumer<Entity> action, Operation<Void> original) {
        if (!RegionTicker.tick((ServerLevel) (Object) this, list, action)) original.call(list, action);
    }

    /** Every way an entity enters the level (addFreshEntity, addWithUUID, during teleport...). */
    @WrapMethod(method = "addEntity")
    private boolean mtmc$addEntityExclusive(Entity entity, Operation<Boolean> original) {
        if (!RegionPhase.needsExclusive()) return original.call(entity);
        return RegionPhase.current().exclusive("add_entity", () -> original.call(entity));
    }

    @WrapMethod(method = "blockEvent")
    private void mtmc$blockEventExclusive(BlockPos pos, Block block, int b0, int b1, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(pos, block, b0, b1);
            return;
        }
        RegionPhase.current().exclusive("block_event", () -> original.call(pos, block, b0, b1));
    }

    /** Also walks navigatingMobs and recomputes the paths of mobs anywhere in the level. */
    @WrapMethod(method = "sendBlockUpdated")
    private void mtmc$blockUpdatedExclusive(BlockPos pos, BlockState old, BlockState current, int flags, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(pos, old, current, flags);
            return;
        }
        RegionPhase.current().exclusive("block_updated", () -> original.call(pos, old, current, flags));
    }

    @WrapMethod(method = "explode")
    private void mtmc$explodeExclusive(Entity source, net.minecraft.world.damagesource.DamageSource damageSource,
                                       net.minecraft.world.level.ExplosionDamageCalculator calc, double x, double y, double z, float r,
                                       boolean fire, net.minecraft.world.level.Level.ExplosionInteraction interaction,
                                       net.minecraft.core.particles.ParticleOptions small, net.minecraft.core.particles.ParticleOptions large,
                                       net.minecraft.util.random.WeightedList<net.minecraft.core.particles.ExplosionParticleInfo> blockParticles,
                                       net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound, Operation<Void> original) {
        if (!RegionPhase.needsExclusive()) {
            original.call(source, damageSource, calc, x, y, z, r, fire, interaction, small, large, blockParticles, sound);
            return;
        }
        RegionPhase.current().exclusive("explode",
            () -> original.call(source, damageSource, calc, x, y, z, r, fire, interaction, small, large, blockParticles, sound));
    }
}
