package dev.mtmc.lag;

import dev.mtmc.Mtmc;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/** Log and tell online ops when a chunk is slowed or back to full speed. */
public final class ThrottleNotices {
    private ThrottleNotices() {}

    /** Called on the level's tick thread; the chat part runs on the server thread. */
    public static void changed(ServerLevel level, LagTracker.ThrottleChange change) {
        LagTracker.ChunkLag c = change.chunk();
        int cx = ChunkPos.getX(c.pos), cz = ChunkPos.getZ(c.pos);
        String what = change.to() == 0 ? "back to full speed"
            : "slowed to 1 tick in " + (1 << change.to()) + (change.to() > change.from() ? "" : " (easing off)");
        Mtmc.LOGGER.warn("[lag] {} chunk [{}, {}] (x {}, z {}) {}: {} ms/t", level.dimension().identifier(), cx, cz,
            (cx << 4) + 8, (cz << 4) + 8, what, String.format(java.util.Locale.ROOT, "%.2f", c.avgTotalMs));
        // tell ops when a chunk is first slowed, hits the slowest level, or is released
        if (!(change.from() == 0 || change.to() == 0 || change.to() == Mtmc.config().lagMaxThrottle)) return;
        MinecraftServer server = level.getServer();
        Component line = Component.literal("[MultithreadMC] ").withStyle(ChatFormatting.GOLD)
            .append(LagCommand.line(0, new LagCommand.Entry(level, c)).append(Component.literal(" - " + what)));
        server.execute(() -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (server.getPlayerList().isOp(p.nameAndId())) p.sendSystemMessage(line);
            }
        });
    }
}
