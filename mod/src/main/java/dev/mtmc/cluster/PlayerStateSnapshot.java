package dev.mtmc.cluster;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueOutput;

/** Detached diagnostic capture only. A live menu must be settled before an actual handoff. */
public final class PlayerStateSnapshot {
    private PlayerStateSnapshot() {}
    public static CompoundTag capture(ServerPlayer player) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Capture requires server thread");
        var problems = new ProblemReporter.Collector();
        var output = TagValueOutput.createWithContext(problems, player.registryAccess());
        player.saveWithoutId(output);
        if (!problems.isEmpty()) throw new IllegalStateException(problems.getReport());
        var envelope = new CompoundTag();
        envelope.putInt("mtmc_schema", 1);
        envelope.putString("player_uuid", player.getUUID().toString());
        envelope.put("player", output.buildResult());
        envelope.putString("dimension", player.level().dimension().identifier().toString());
        envelope.putInt("menu_id", player.containerMenu.containerId);
        var ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        envelope.put("cursor", ItemStack.OPTIONAL_CODEC.encodeStart(ops, player.containerMenu.getCarried().copy()).getOrThrow());
        var slots = new ListTag();
        for (var slot : player.containerMenu.slots) {
            var entry = new CompoundTag();
            entry.putInt("index", slot.index);
            entry.put("item", ItemStack.OPTIONAL_CODEC.encodeStart(ops, slot.getItem().copy()).getOrThrow());
            slots.add(entry);
        }
        envelope.put("menu_slots", slots);
        NbtUtils.addCurrentDataVersion(envelope);
        return envelope;
    }
}
