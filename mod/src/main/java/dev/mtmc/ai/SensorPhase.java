package dev.mtmc.ai;

import dev.mtmc.Mtmc;
import dev.mtmc.MtmcConfig;
import dev.mtmc.lag.LagAccess;
import dev.mtmc.mixin.BrainAccessor;

import dev.mtmc.mixin.ServerChunkCacheInvoker;
import dev.mtmc.mixin.ServerLevelTickListAccessor;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.sensing.*;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Parallel sensor phase (config sensorPhase, /mtmc sensors). Before a level ticks its
 * entities, the sensors due this tick for every mob whose brain will tick run on a pool while
 * the level's thread runs a share of them too; then each mob's normal brain tick skips the
 * sensors that already ran (SensorMixin).
 *
 * <p>Safe because sensors read the world (entities, block states, players) and write only their
 * own mob's brain memories, and nothing else in the level runs meanwhile. Only whitelisted
 * sensor classes, read in the 26.2 source, run early (POI-reading sensors don't). Per mob, the
 * due sensors run in the brain's own order up to the first one that isn't whitelisted, which
 * then runs in the brain tick as usual, so sensors that read an earlier sensor's memory
 * (piglin, hoglin, adult, golem...) still see it from this tick.
 *
 * <p>Pool threads: chunk reads are read-only lookups of loaded chunks (a sensor wanting an
 * unloaded chunk falls back: that mob's remaining sensors run in its brain tick); Sensor's
 * shared static TargetingConditions resolve to thread-local copies (SensorMixin); entity
 * sections' by-class lookup cache, which fills lazily, is locked per section while a phase runs
 * (ClassInstanceMultiMapMixin).
 *
 * <p>Difference from vanilla: early-run sensors see the world as it is at the start of the
 * entity phase, not after the mobs ticked before this one in the same tick moved.
 */
public final class SensorPhase {
    private static final ThreadLocal<ServerLevel> SENSOR_LEVEL = new ThreadLocal<>();
    private static final ThreadLocal<Map<TargetingConditions, TargetingConditions>> LOCAL_TC = ThreadLocal.withInitial(IdentityHashMap::new);
    private static final Set<Class<?>> WHITELIST = Set.of(
        NearestLivingEntitySensor.class, PlayerSensor.class, NearestItemSensor.class, HoglinSpecificSensor.class,
        PiglinSpecificSensor.class, PiglinBruteSpecificSensor.class, VillagerHostilesSensor.class, VillagerBabiesSensor.class,
        GolemSensor.class, HurtBySensor.class, AdultSensor.class, AdultSensorAnyType.class, AxolotlAttackablesSensor.class,
        FrogAttackablesSensor.class, BreezeAttackEntitySensor.class, IsInWaterSensor.class, TemptingSensor.class,
        WardenEntitySensor.class, MobSensor.class, DummySensor.class);
    private static final int BATCH = 16;
    private static volatile ForkJoinPool pool;
    private static int poolSize;

    public static final LongAdder MOBS = new LongAdder();
    public static final LongAdder SENSORS = new LongAdder();
    public static final LongAdder FALLBACKS = new LongAdder();
    public static final LongAdder PHASES = new LongAdder();
    /** Sensor phases running now, in any level (ClassInstanceMultiMapMixin locks lookups while > 0). */
    public static final AtomicInteger ACTIVE = new AtomicInteger();

    /** Thrown on a sensor thread that needs an unloaded chunk: that mob's sensors go back to its brain tick. */
    public static final class Fallback extends RuntimeException {
        static final Fallback INSTANCE = new Fallback();

        private Fallback() {
            super("sensor needs an unloaded chunk", null, false, false);
        }
    }

    private SensorPhase() {}

    /** On a thread running sensors for this level (pool thread, or the level thread during its share). */
    public static boolean onSensorThread(ServerChunkCache cache) {
        ServerLevel l = SENSOR_LEVEL.get();
        return l != null && l.getChunkSource() == cache;
    }

    /** Sensor's shared static targeting conditions, as a per-thread copy on sensor threads. */
    public static TargetingConditions local(TargetingConditions shared) {
        if (SENSOR_LEVEL.get() == null) return shared;
        return LOCAL_TC.get().computeIfAbsent(shared, TargetingConditions::copy);
    }

    /** Read-only chunk lookup for sensor threads: loaded chunks only, no cache writes, no loading. */
    public static ChunkAccess readOnlyChunk(ServerChunkCache cache, int x, int z, ChunkStatus status, boolean load) {
        ChunkHolder holder = ((ServerChunkCacheInvoker) cache).mtmc$getVisibleChunkIfPresent(ChunkPos.pack(x, z));
        ChunkAccess chunk = holder == null ? null : holder.getChunkIfPresent(status);
        if (chunk == null && load) throw Fallback.INSTANCE;
        return chunk;
    }

    private record Job(LivingEntity mob, List<Sensor<?>> sensors) {}

    /** Called by ServerLevel.tick right before the entity tick loop, on the level's thread. */
    public static void run(ServerLevel level) {
        MtmcConfig cfg = Mtmc.config();
        if (!cfg.sensorPhase || !level.tickRateManager().runsNormally()) return;
        long time = level.getGameTime();
        List<Job> jobs = new ArrayList<>();
        var lag = ((LagAccess) level).mtmc$lag();
        ((ServerLevelTickListAccessor) level).mtmc$entityTickList().forEach(entity -> {
            if (!(entity instanceof Mob mob) || !willTickBrain(level, mob) || lag.skipNow(mob.chunkPosition().pack())) return;
            List<Sensor<?>> due = null;
            for (Sensor<?> sensor : ((BrainAccessor) mob.getBrain()).mtmc$sensors().values()) {
                if (((SensorAccess) sensor).mtmc$timeToTick() > 1) continue; // not due: the brain tick only counts it down
                if (!WHITELIST.contains(sensor.getClass())) break; // keep the brain's order: the rest run in the brain tick
                if (due == null) due = new ArrayList<>(4);
                due.add(sensor);
            }
            if (due != null) jobs.add(new Job(mob, due));
        });
        if (jobs.size() < cfg.sensorPhaseMin) return;
        PHASES.increment();
        ACTIVE.incrementAndGet();
        try {
            runJobs(level, jobs, time, pool(cfg));
        } finally {
            ACTIVE.decrementAndGet();
        }
    }

    private static void runJobs(ServerLevel level, List<Job> jobs, long time, ForkJoinPool p) {
        List<Future<?>> futures = new ArrayList<>();
        int batches = (jobs.size() + BATCH - 1) / BATCH;
        // the last batch runs on this (the level's) thread
        for (int b = 0; b < batches - 1; b++) {
            List<Job> slice = jobs.subList(b * BATCH, Math.min(jobs.size(), (b + 1) * BATCH));
            futures.add(p.submit(() -> runBatch(level, slice, time)));
        }
        Throwable failure = null;
        try {
            runBatch(level, jobs.subList((batches - 1) * BATCH, jobs.size()), time);
        } catch (Throwable t) {
            failure = t;
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                if (failure == null) failure = e.getCause();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (failure == null) failure = e;
            }
        }
        if (failure instanceof ReportedException re) throw re;
        if (failure != null) throw new ReportedException(CrashReport.forThrowable(failure, "Ticking sensors (parallel sensor phase)"));
    }

    private static boolean willTickBrain(ServerLevel level, Mob mob) {
        if (mob.isRemoved() || mob.isNoAi() || mob.isDeadOrDying() || level.tickRateManager().isEntityFrozen(mob)) return false;
        if (mob.getBrain() == null || ((BrainAccessor) mob.getBrain()).mtmc$sensors().isEmpty()) return false;
        return level.getChunkSource().chunkMap.getDistanceManager().inEntityTickingRange(mob.chunkPosition().pack());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void runBatch(ServerLevel level, List<Job> jobs, long time) {
        SENSOR_LEVEL.set(level);
        try {
            for (Job job : jobs) {
                MOBS.increment();
                for (Sensor sensor : job.sensors()) {
                    SensorAccess access = (SensorAccess) sensor;
                    long before = access.mtmc$timeToTick();
                    try {
                        sensor.tick(level, job.mob());
                        access.mtmc$setPreTicked(time);
                        SENSORS.increment();
                    } catch (Fallback f) {
                        access.mtmc$setTimeToTick(before); // the brain tick runs it (and the rest) as usual
                        FALLBACKS.increment();
                        break;
                    }
                }
            }
        } finally {
            SENSOR_LEVEL.remove();
        }
    }

    private static synchronized ForkJoinPool pool(MtmcConfig cfg) {
        int want = cfg.sensorThreads > 0 ? cfg.sensorThreads : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        if (pool == null || poolSize != want) {
            ForkJoinPool old = pool;
            AtomicInteger ids = new AtomicInteger();
            pool = new ForkJoinPool(want, fjp -> {
                var t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(fjp);
                t.setName("MTMC Sensor #" + ids.incrementAndGet());
                t.setDaemon(true);
                return t;
            }, null, false);
            poolSize = want;
            if (old != null) old.shutdown();
        }
        return pool;
    }
}
