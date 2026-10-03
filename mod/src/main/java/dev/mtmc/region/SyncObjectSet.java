package dev.mtmc.region;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

/**
 * An ObjectOpenHashSet whose single-element operations are synchronized, for Lithium's level-wide
 * tracker interners (block-change and entity-movement trackers), which a mob's block cache and
 * item or collider lookups register into and remove from inside its tick. Iteration is not
 * synchronized.
 */
public final class SyncObjectSet<K> extends ObjectOpenHashSet<K> {
    @Override
    public synchronized K addOrGet(K k) {
        return super.addOrGet(k);
    }

    @Override
    public synchronized K get(Object k) {
        return super.get(k);
    }

    @Override
    public synchronized boolean add(K k) {
        return super.add(k);
    }

    @Override
    public synchronized boolean remove(Object k) {
        return super.remove(k);
    }

    @Override
    public synchronized boolean contains(Object k) {
        return super.contains(k);
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
