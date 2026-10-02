package dev.mtmc.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.mtmc.ai.SensorAccess;
import dev.mtmc.ai.SensorPhase;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Parallel sensor phase support (SensorPhase): skip a sensor in the brain tick if it already
 * ran early this tick, and give sensor threads their own copies of Sensor's shared static
 * TargetingConditions (whose range every sensor tick overwrites).
 */
@Mixin(Sensor.class)
abstract class SensorMixin implements SensorAccess {
    @Shadow
    private long timeToTick;

    @Unique
    private long mtmc$preTicked = Long.MIN_VALUE;

    @Override
    public long mtmc$timeToTick() {
        return timeToTick;
    }

    @Override
    public void mtmc$setTimeToTick(long ticks) {
        timeToTick = ticks;
    }

    @Override
    public void mtmc$setPreTicked(long gameTime) {
        mtmc$preTicked = gameTime;
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void mtmc$skipIfRanEarly(ServerLevel level, LivingEntity body, CallbackInfo ci) {
        if (mtmc$preTicked == level.getGameTime()) ci.cancel();
    }

    @ModifyExpressionValue(method = "updateTargetingConditionRanges", at = {
        @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;TARGET_CONDITIONS:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
        @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
        @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;ATTACK_TARGET_CONDITIONS:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
        @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
        @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;ATTACK_TARGET_CONDITIONS_IGNORE_LINE_OF_SIGHT:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
        @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_AND_LINE_OF_SIGHT:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;")})
    private TargetingConditions mtmc$localRanges(TargetingConditions shared) {
        return SensorPhase.local(shared);
    }

    @ModifyExpressionValue(method = {"isEntityTargetable", "isEntityAttackable", "isEntityAttackableIgnoringLineOfSight"},
        at = {
        @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;TARGET_CONDITIONS:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
        @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
        @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;ATTACK_TARGET_CONDITIONS:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
        @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
        @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;ATTACK_TARGET_CONDITIONS_IGNORE_LINE_OF_SIGHT:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
        @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_AND_LINE_OF_SIGHT:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;")})
    private static TargetingConditions mtmc$localTests(TargetingConditions shared) {
        return SensorPhase.local(shared);
    }
}
