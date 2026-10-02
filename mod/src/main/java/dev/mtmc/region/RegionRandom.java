package dev.mtmc.region;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * A level's shared {@code random}: the original on the level's own thread, a per-thread one on
 * region threads. Vanilla's LegacyRandomSource throws when two threads use it at once, and the
 * level's random is used from inside entity ticks (sounds, drops, loot, particles).
 */
public final class RegionRandom implements RandomSource {
    private final RandomSource base;
    private final ThreadLocal<RandomSource> local = ThreadLocal.withInitial(RandomSource::create);

    public RegionRandom(RandomSource base) {
        this.base = base;
    }

    private RandomSource r() {
        return RegionPhase.current() != null ? local.get() : base;
    }

    @Override
    public RandomSource fork() {
        return r().fork();
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return r().forkPositional();
    }

    @Override
    public void setSeed(long seed) {
        r().setSeed(seed);
    }

    @Override
    public int nextInt() {
        return r().nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return r().nextInt(bound);
    }

    @Override
    public long nextLong() {
        return r().nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return r().nextBoolean();
    }

    @Override
    public float nextFloat() {
        return r().nextFloat();
    }

    @Override
    public double nextDouble() {
        return r().nextDouble();
    }

    @Override
    public double nextGaussian() {
        return r().nextGaussian();
    }
}
