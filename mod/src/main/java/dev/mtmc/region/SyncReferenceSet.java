package dev.mtmc.region;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

/**
 * A ReferenceOpenHashSet whose single-element operations are synchronized, for a level-wide set
 * that entities add themselves to and remove themselves from inside their own tick (Lithium's
 * active navigations). Iteration is not synchronized: it must only happen while no region thread
 * runs, or with the level held exclusively.
 */
public final class SyncReferenceSet<K> extends ReferenceOpenHashSet<K> {
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
