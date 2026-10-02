package dev.mtmc.region;

import dev.mtmc.Mtmc;
import dev.mtmc.MtmcConfig;
import dev.mtmc.ai.SensorPhase;
import dev.mtmc.lag.LagAccess;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.entity.EntityTickList;

/**
 * Region ticking inside one level (config {@code regions}, {@code /mtmc regions}): the level's
 * entity loop is split into regions of nearby entities and the regions tick in parallel, each
 * one whole on one thread, in vanilla order within it (Folia's idea, for the entity phase).
 *
 * <p><b>Regions.</b> The level is cut into square cells of {@code regionCellChunks} chunks. A
 * cell with a ticking entity in it (a passenger counts where its root vehicle is) is occupied;
 * occupied cells that touch, diagonals included, belong to the same region. So two regions are
 * always at least one whole cell apart (64 blocks with the default 4 chunks), much more than an
 * entity moves, reaches or explodes in one tick. Regions are rebuilt every tick, so they merge and
 * split as players and mobs move, at no cost.
 *
 * <p><b>What runs where.</b> The vanilla per-entity body (despawn check, ticking-range check,
 * guardEntityTick) is reused as is. Shared level state is only written through
 * {@link RegionPhase#exclusive} or deferred to after the phase ({@link RegionPhase#defer}); see
 * RegionPhase and the region mixins for the list. Block entities, scheduled ticks, random ticks,
 * spawning and the chunk system are still ticked on the level's thread, before and after.
 */
public final class RegionTicker {
    private static volatile ExecutorService pool;
    private static int poolSize;
    private static final AtomicInteger ESCAPE_LOGS = new AtomicInteger();

    private RegionTicker() {}

    /** One region: its entities in vanilla order. */
    private record Region(int id, List<Entity> entities) {}

    /**
     * Called instead of {@code entityTickList.forEach(action)} in ServerLevel.tick, on the level's
     * thread. False = not run (the caller runs the vanilla loop).
     */
    public static boolean tick(ServerLevel level, EntityTickList list, Consumer<Entity> action) {
        MtmcConfig cfg = Mtmc.config();
        if (!cfg.regions) return false;
        if (RegionPhase.current() != null) return false;
        if (Profiler.get() != InactiveProfiler.INSTANCE) {
            // vanilla's loop body pushes and pops the level thread's profiler: one thread at a time
            RegionStats.serial("profiler");
            return false;
        }
        List<Entity> all = new ArrayList<>(1024);
        list.forEach(all::add);
        if (all.size() < cfg.regionMinEntities) {
            RegionStats.serial("few_entities");
            return false;
        }
        int cell = Math.max(1, cfg.regionCellChunks);
        Long2IntOpenHashMap cellIndex = new Long2IntOpenHashMap(256);
        cellIndex.defaultReturnValue(-1);
        LongArrayList cells = new LongArrayList();
        int[] entityCell = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Entity e = all.get(i);
            if (e instanceof EnderDragon) {
                // the dragon fight reaches the whole island (crystals, perches, every player)
                RegionStats.serial("ender_dragon");
                return false;
            }
            long key = cellKey(e.getRootVehicle(), cell);
            int idx = cellIndex.get(key);
            if (idx < 0) {
                idx = cells.size();
                cellIndex.put(key, idx);
                cells.add(key);
            }
            entityCell[i] = idx;
        }
        // union occupied cells that touch (8 neighbours)
        int[] parent = new int[cells.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int i = 0; i < cells.size(); i++) {
            long key = cells.getLong(i);
            int cx = (int) key, cz = (int) (key >> 32);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx | dz) == 0) continue;
                    int j = cellIndex.get(pack(cx + dx, cz + dz));
                    if (j >= 0) union(parent, i, j);
                }
            }
        }
        int[] regionOfCell = new int[cells.size()];
        Arrays.fill(regionOfCell, -1);
        int regionCount = 0;
        int[] rootRegion = new int[cells.size()];
        Arrays.fill(rootRegion, -1);
        for (int i = 0; i < cells.size(); i++) {
            int r = find(parent, i);
            if (rootRegion[r] < 0) rootRegion[r] = regionCount++;
            regionOfCell[i] = rootRegion[r];
        }
        if (regionCount < 2) {
            RegionStats.serial("one_region");
            return false;
        }
        List<List<Entity>> lists = new ArrayList<>(regionCount);
        for (int r = 0; r < regionCount; r++) lists.add(new ArrayList<>());
        for (int i = 0; i < all.size(); i++) lists.get(regionOfCell[entityCell[i]]).add(all.get(i));
        Region[] order = new Region[regionCount];
        int biggest = 0;
        for (int r = 0; r < regionCount; r++) {
            order[r] = new Region(r, lists.get(r));
            biggest = Math.max(biggest, lists.get(r).size());
        }
        // biggest first, so the longest region starts right away (LPT)
        Arrays.sort(order, (a, b) -> Integer.compare(b.entities.size(), a.entities.size()));
        Long2IntOpenHashMap cellRegion = new Long2IntOpenHashMap(cells.size());
        cellRegion.defaultReturnValue(-1);
        for (int i = 0; i < cells.size(); i++) cellRegion.put(cells.getLong(i), regionOfCell[i]);

        run(level, order, action, cellRegion, cell, cfg);
        RegionStats.PHASES.increment();
        RegionStats.REGIONS.add(regionCount);
        RegionStats.ENTITIES.add(all.size());
        RegionStats.BIGGEST.add(biggest);
        return true;
    }

    private static final class Run {
        final AtomicInteger next = new AtomicInteger();
        volatile Throwable failure;

        synchronized void fail(Throwable t) {
            if (failure == null) failure = t;
        }
    }

    private static void run(ServerLevel level, Region[] order, Consumer<Entity> action, Long2IntOpenHashMap cellRegion, int cell,
                            MtmcConfig cfg) {
        RegionPhase phase = new RegionPhase(level, Thread.currentThread());
        Run run = new Run();
        long start = System.nanoTime();
        RegionPhase.RUNNING.incrementAndGet();
        SensorPhase.ACTIVE.incrementAndGet(); // entity sections' lazy by-class cache takes its lock while > 0
        try {
            ExecutorService exec = pool(cfg);
            int helpers = Math.min(order.length - 1, poolSize);
            for (int h = 0; h < helpers; h++) {
                exec.execute(() -> participate(phase, order, action, cellRegion, cell, run));
            }
            participate(phase, order, action, cellRegion, cell, run);
            phase.awaitIdle();
        } finally {
            SensorPhase.ACTIVE.decrementAndGet();
            RegionPhase.RUNNING.decrementAndGet();
        }
        RegionStats.PHASE_NANOS.add(System.nanoTime() - start);
        // in the order asked, on the level's thread, with the owner fields back on it
        phase.drainDeferred();
        ((LagAccess) level).mtmc$lag().mergeRegionSamples();
        Throwable failure = run.failure;
        if (failure instanceof ReportedException re) throw re;
        if (failure != null) throw new ReportedException(CrashReport.forThrowable(failure, "Ticking entities (region phase)"));
    }

    private static void participate(RegionPhase phase, Region[] order, Consumer<Entity> action, Long2IntOpenHashMap cellRegion,
                                    int cell, Run run) {
        if (!phase.enter(() -> run.failure == null && run.next.get() < order.length)) return;
        long t0 = System.nanoTime();
        try {
            int i;
            while (run.failure == null && (i = run.next.getAndIncrement()) < order.length) {
                Region region = order[i];
                for (Entity e : region.entities) {
                    phase.safepoint();
                    long before = cellKey(e.getRootVehicle(), cell);
                    action.accept(e);
                    if (!e.isRemoved()) {
                        long after = cellKey(e.getRootVehicle(), cell);
                        if (after != before) checkEscape(e, region.id, cellRegion.get(after));
                    }
                }
            }
        } catch (Throwable t) {
            run.fail(t);
        } finally {
            RegionStats.WORK_NANOS.add(System.nanoTime() - t0);
            phase.leave();
        }
    }

    private static void checkEscape(Entity e, int region, int target) {
        if (target < 0 || target == region) return;
        RegionStats.ESCAPES.increment();
        if (ESCAPE_LOGS.incrementAndGet() <= 10) {
            Mtmc.LOGGER.warn("[regions] {} moved into another region's cell during its tick (now at {}); the region buffer was too small for it",
                e, e.position());
        }
    }

    static long cellKey(Entity e, int cell) {
        return pack(Math.floorDiv(SectionPos.blockToSectionCoord(e.getBlockX()), cell),
            Math.floorDiv(SectionPos.blockToSectionCoord(e.getBlockZ()), cell));
    }

    private static long pack(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
    }

    public static int threads(MtmcConfig cfg) {
        return cfg.regionThreads > 0 ? cfg.regionThreads : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    private static synchronized ExecutorService pool(MtmcConfig cfg) {
        int want = threads(cfg);
        if (pool == null || poolSize != want) {
            ExecutorService old = pool;
            AtomicInteger ids = new AtomicInteger();
            pool = Executors.newFixedThreadPool(want, r -> {
                Thread t = new Thread(r, "MTMC Region #" + ids.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            poolSize = want;
            if (old != null) old.shutdown();
        }
        return pool;
    }
}
