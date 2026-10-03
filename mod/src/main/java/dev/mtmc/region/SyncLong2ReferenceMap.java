package dev.mtmc.region;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;

/**
 * A Long2ReferenceOpenHashMap whose single-key operations are synchronized, for a level-wide map
 * that entity ticks write to (Lithium's chunk-section change callbacks, created when a mob's block
 * cache first watches a section). Two region threads growing the plain map at once threw
 * ArrayIndexOutOfBoundsException. Iteration is not synchronized.
 */
public final class SyncLong2ReferenceMap<V> extends Long2ReferenceOpenHashMap<V> {
    @Override
    public synchronized V put(long k, V v) {
        return super.put(k, v);
    }

    @Override
    public synchronized V putIfAbsent(long k, V v) {
        return super.putIfAbsent(k, v);
    }

    @Override
    public synchronized V remove(long k) {
        return super.remove(k);
    }

    @Override
    public synchronized V get(long k) {
        return super.get(k);
    }

    @Override
    public synchronized boolean containsKey(long k) {
        return super.containsKey(k);
    }

    @Override
    public synchronized int size() {
        return super.size();
    }

    @Override
    public synchronized void clear() {
        super.clear();
    }
}
