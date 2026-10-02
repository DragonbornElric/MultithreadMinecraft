package dev.mtmc.region;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Deaths run one at a time, server-wide. Kill hooks are where mods most often keep "server thread
 * only" state: Emma-EndInv's death-loot capture keeps the dying mob and its drops in static
 * fields, and jobs, lifesteal and kill rewards write per-player or server-wide maps. With parallel
 * dimensions two deaths could already overlap; with regions, two in one level can.
 *
 * <p>On a region thread the level is taken first (exclusive) and the lock second, so a thread
 * waiting for the lock is never one that an exclusive section is waiting for.
 */
public final class Deaths {
    private static final ReentrantLock LOCK = new ReentrantLock();

    private Deaths() {}

    public static void run(Runnable death) {
        run(() -> {
            death.run();
            return null;
        });
    }

    public static <T> T run(Supplier<T> death) {
        RegionPhase phase = RegionPhase.needsExclusive() ? RegionPhase.current() : null;
        if (phase != null) return phase.exclusive("death", () -> locked(death));
        return locked(death);
    }

    private static <T> T locked(Supplier<T> death) {
        LOCK.lock();
        try {
            return death.get();
        } finally {
            LOCK.unlock();
        }
    }
}
