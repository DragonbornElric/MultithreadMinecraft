package dev.mtmc.ai;

/** Implemented by Sensor (SensorMixin). */
public interface SensorAccess {
    long mtmc$timeToTick();

    void mtmc$setTimeToTick(long ticks);

    void mtmc$setPreTicked(long gameTime);
}
