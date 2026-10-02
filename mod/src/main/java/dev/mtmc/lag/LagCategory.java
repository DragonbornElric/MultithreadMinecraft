package dev.mtmc.lag;

/** What a chunk's tick time was spent on. */
public enum LagCategory {
    ENTITIES("entities"),
    BLOCK_ENTITIES("block entities"),
    BLOCK_TICKS("block ticks"),
    FLUID_TICKS("fluid ticks"),
    BLOCK_EVENTS("block events"),
    CHUNK_TICK("random ticks"),
    /** Count only: block changes that queued a light check (light is computed off the tick thread). */
    LIGHT("light updates");

    public final String label;

    LagCategory(String label) {
        this.label = label;
    }

    static final LagCategory[] ALL = values();
}
