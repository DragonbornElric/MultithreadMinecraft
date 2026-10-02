package dev.mtmc.lag;

import dev.mtmc.Mtmc;
import dev.mtmc.MtmcConfig;
import java.util.Iterator;
import java.util.List;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Per-chunk caps for NEW entities (ServerLevel.addFreshEntity; entities loaded from disk or
 * arriving through a portal are never touched). What happens at the cap depends on what would
 * be lost by refusing:
 *
 * <ul>
 *   <li>primed TNT, falling blocks: the block is already gone, so they <b>wait</b> (FIFO) and are
 *       added on a later tick when the chunk has room. Explosions still happen, sand still lands.
 *   <li>items: merged into a same-item stack in the chunk if one has room, else they <b>wait</b>.
 *       Nobody loses items to a cap.
 *   <li>vehicles (minecarts, boats), armor stands: <b>refused and dropped as their item</b>, so
 *       stacks can't grow and nobody loses the item.
 *   <li>mobs: refused (eggs, breeding, spawners in that chunk stop adding).
 * </ul>
 *
 * Waiting entities are added before every save (autosave, shutdown), so none are lost. Runs on
 * the level's own tick thread or on the server thread between ticks (commands), never both.
 */
public final class EntityCaps {
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    private static final int MAX_PENDING = 20_000;
    private static final int RELEASE_PER_TICK = 256;

    private EntityCaps() {}

    enum Cat {
        ITEM(ItemEntity.class), TNT(PrimedTnt.class), FALLING_BLOCK(FallingBlockEntity.class),
        VEHICLE(VehicleEntity.class), ARMOR_STAND(ArmorStand.class), MOB(Mob.class);

        final Class<? extends Entity> type;

        Cat(Class<? extends Entity> type) {
            this.type = type;
        }

        int cap(MtmcConfig c) {
            return switch (this) {
                case ITEM -> c.capItems;
                case TNT -> c.capTnt;
                case FALLING_BLOCK -> c.capFallingBlocks;
                case VEHICLE -> c.capVehicles;
                case ARMOR_STAND -> c.capArmorStands;
                case MOB -> c.capMobs;
            };
        }

        static Cat of(Entity e) {
            for (Cat c : values()) if (c.type.isInstance(e)) return c;
            return null;
        }
    }

    /** Decide for a fresh entity. Returns null to add it normally, else the value addFreshEntity returns. */
    public static Boolean onAdd(ServerLevel level, Entity entity) {
        MtmcConfig cfg = Mtmc.config();
        if (!cfg.lagCaps || BYPASS.get() || entity.isVehicle() && entity.hasPassenger(p -> p instanceof net.minecraft.world.entity.player.Player)) return null;
        Cat cat = Cat.of(entity);
        if (cat == null) return null;
        int cap = cat.cap(cfg);
        if (cap <= 0) return null;
        List<? extends Entity> present = inChunk(level, entity, cat);
        if (present.size() < cap) return null;
        LagTracker tracker = ((LagAccess) level).mtmc$lag();
        LagTracker.ChunkLag lag = tracker.getOrCreate(entity.chunkPosition().pack());
        switch (cat) {
            case ITEM -> {
                ItemStack stack = ((ItemEntity) entity).getItem();
                for (Entity e : present) {
                    ItemEntity other = (ItemEntity) e;
                    ItemStack o = other.getItem();
                    if (ItemEntity.areMergable(o, stack) && o.getCount() + stack.getCount() <= o.getMaxStackSize()) {
                        other.setItem(o.copyWithCount(o.getCount() + stack.getCount()));
                        MtmcCapStats.count("item_merged");
                        return true;
                    }
                }
                return delay(tracker, lag, entity, "item_delayed");
            }
            case TNT -> {
                return delay(tracker, lag, entity, "tnt_delayed");
            }
            case FALLING_BLOCK -> {
                return delay(tracker, lag, entity, "falling_block_delayed");
            }
            case VEHICLE, ARMOR_STAND -> {
                ItemStack drop = entity.getPickResult();
                if (drop != null && !drop.isEmpty()) {
                    level.addFreshEntity(new ItemEntity(level, entity.getX(), entity.getY(), entity.getZ(), drop.copy()));
                }
                MtmcCapStats.count(cat == Cat.VEHICLE ? "vehicle_refused" : "armor_stand_refused");
                return false;
            }
            default -> {
                MtmcCapStats.count("mob_refused");
                return false;
            }
        }
    }

    private static boolean delay(LagTracker tracker, LagTracker.ChunkLag lag, Entity entity, String what) {
        if (tracker.pending.size() >= MAX_PENDING) {
            MtmcCapStats.count(what.replace("_delayed", "_dropped_queue_full"));
            return false;
        }
        tracker.pending.addLast(entity);
        lag.delayed++;
        MtmcCapStats.count(what);
        return true;
    }

    /**
     * The chunk's entities of this category, straight from its entity sections. Not through
     * level.getEntities: that only sees chunks whose entities are already accessible, so a chunk
     * that was just loaded would read as empty and slip past the cap.
     */
    @SuppressWarnings("unchecked")
    private static List<? extends Entity> inChunk(ServerLevel level, Entity entity, Cat cat) {
        var manager = ((dev.mtmc.mixin.ServerLevelEntityManagerAccessor) level).mtmc$entityManager();
        EntitySectionStorage<Entity> storage =
            ((dev.mtmc.mixin.PersistentEntitySectionManagerAccessor<Entity>) (Object) manager).mtmc$sectionStorage();
        return storage.getExistingSectionsInChunk(entity.chunkPosition().pack())
            .flatMap(EntitySection::getEntities)
            .filter(e -> cat.type.isInstance(e) && !e.isRemoved())
            .toList();
    }

    /** Start of a level tick: add waiting entities, oldest first, where their chunk has room. */
    public static void release(ServerLevel level) {
        LagTracker tracker = ((LagAccess) level).mtmc$lag();
        if (tracker.pending.isEmpty()) return;
        MtmcConfig cfg = Mtmc.config();
        int added = 0;
        Iterator<Entity> it = tracker.pending.iterator();
        while (it.hasNext() && added < RELEASE_PER_TICK) {
            Entity e = it.next();
            Cat cat = Cat.of(e);
            if (cfg.lagCaps && cat != null && inChunk(level, e, cat).size() >= cat.cap(cfg)) continue;
            it.remove();
            addNow(level, tracker, e);
            added++;
        }
    }

    /** Before a save: everything waiting goes into the world, caps or not. */
    public static void flushAll(ServerLevel level) {
        LagTracker tracker = ((LagAccess) level).mtmc$lag();
        Entity e;
        while ((e = tracker.pending.pollFirst()) != null) addNow(level, tracker, e);
    }

    private static void addNow(ServerLevel level, LagTracker tracker, Entity e) {
        LagTracker.ChunkLag lag = tracker.get(e.chunkPosition().pack());
        if (lag != null && lag.delayed > 0) lag.delayed--;
        BYPASS.set(true);
        try {
            level.addFreshEntity(e);
        } finally {
            BYPASS.set(false);
        }
    }
}
