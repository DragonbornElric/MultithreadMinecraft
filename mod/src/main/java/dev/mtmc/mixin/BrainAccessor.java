package dev.mtmc.mixin;

import java.util.Map;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Brain.class)
public interface BrainAccessor {
    @Accessor("sensors")
    Map<SensorType<?>, Sensor<?>> mtmc$sensors();
}
