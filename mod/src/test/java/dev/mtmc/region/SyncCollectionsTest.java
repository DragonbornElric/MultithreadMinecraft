package dev.mtmc.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The synchronized collections that stand in for Lithium's level-wide ones under region ticking:
 * several threads growing and shrinking them at once (what two region threads did to the plain
 * fastutil ones: ArrayIndexOutOfBoundsException in rehash) leave them consistent.
 */
class SyncCollectionsTest {
    private static final int THREADS = 4, PER_THREAD = 50_000;

    private static void hammer(IntConsumer body) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int id = t;
            Thread th = new Thread(() -> {
                try {
                    go.await();
                    body.accept(id);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            th.start();
            threads.add(th);
        }
        go.countDown();
        for (Thread th : threads) th.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    interface IntConsumer {
        void accept(int id) throws Exception;
    }

    @Test
    void sectionCallbackMapGrowsAndShrinksFromManyThreads() throws Exception {
        SyncLong2ReferenceMap<Object> map = new SyncLong2ReferenceMap<>();
        Object v = new Object();
        hammer(id -> {
            for (int round = 0; round < 3; round++) {
                for (long i = 0; i < PER_THREAD; i++) map.put(i * THREADS + id, v);
                for (long i = 0; i < PER_THREAD; i++) map.remove(i * THREADS + id);
            }
            for (long i = 0; i < PER_THREAD; i++) map.put(i * THREADS + id, v);
        });
        assertEquals(THREADS * PER_THREAD, map.size());
        for (long k = 0; k < (long) THREADS * PER_THREAD; k++) assertSame(v, map.get(k));
        assertNull(map.get(-1L));
    }

    @Test
    void trackerInternerGrowsAndShrinksFromManyThreads() throws Exception {
        SyncObjectSet<Long> set = new SyncObjectSet<>();
        hammer(id -> {
            for (int round = 0; round < 3; round++) {
                for (long i = 0; i < PER_THREAD; i++) set.addOrGet(i * THREADS + id);
                for (long i = 0; i < PER_THREAD; i++) set.remove(i * THREADS + id);
            }
            for (long i = 0; i < PER_THREAD; i++) set.addOrGet(i * THREADS + id);
        });
        assertEquals(THREADS * PER_THREAD, set.size());
        Long canonical = set.addOrGet(7L);
        assertSame(canonical, set.addOrGet(Long.valueOf(7L)));
    }
}
