package dev.mtmc.lag;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mtmc.Mtmc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * /mtmc lag [top [n]] | here | on | off | reset | release | throttle [on|off|budget|busy|hard <ms>]
 *
 * <p>Runs on the server thread between ticks, so reading the per-level trackers is safe.
 */
public final class LagCommand {
    private LagCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("lag")
            .executes(c -> top(c.getSource(), 10))
            .then(Commands.literal("top")
                .executes(c -> top(c.getSource(), 10))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                    .executes(c -> top(c.getSource(), IntegerArgumentType.getInteger(c, "count")))))
            .then(Commands.literal("here").executes(c -> here(c.getSource())))
            .then(Commands.literal("on").executes(c -> toggle(c.getSource(), true)))
            .then(Commands.literal("off").executes(c -> toggle(c.getSource(), false)))
            .then(Commands.literal("reset").executes(c -> reset(c.getSource())))
            .then(Commands.literal("release").executes(c -> release(c.getSource())))
            .then(Commands.literal("throttle")
                .executes(c -> throttleStatus(c.getSource()))
                .then(Commands.literal("on").executes(c -> setThrottle(c.getSource(), true)))
                .then(Commands.literal("off").executes(c -> setThrottle(c.getSource(), false)))
                .then(Commands.literal("budget").then(Commands.argument("ms", DoubleArgumentType.doubleArg(0.1, 1000))
                    .executes(c -> setBudget(c.getSource(), "budget", DoubleArgumentType.getDouble(c, "ms")))))
                .then(Commands.literal("busy").then(Commands.argument("ms", DoubleArgumentType.doubleArg(0, 1000))
                    .executes(c -> setBudget(c.getSource(), "busy", DoubleArgumentType.getDouble(c, "ms")))))
                .then(Commands.literal("hard").then(Commands.argument("ms", DoubleArgumentType.doubleArg(0.1, 1000))
                    .executes(c -> setBudget(c.getSource(), "hard", DoubleArgumentType.getDouble(c, "ms"))))));
    }

    /** A chunk's lag with the level it is in. */
    public record Entry(ServerLevel level, LagTracker.ChunkLag lag) {}

    /** Worst chunks over all levels. */
    public static List<Entry> worst(MinecraftServer server, int n) {
        List<Entry> all = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (LagTracker.ChunkLag c : ((LagAccess) level).mtmc$lag().top(n)) all.add(new Entry(level, c));
        }
        all.sort(Comparator.comparingDouble((Entry e) -> e.lag().avgTotalMs).reversed());
        return all.subList(0, Math.min(n, all.size()));
    }

    private static int top(CommandSourceStack src, int n) {
        if (!Mtmc.config().lagAccounting) {
            src.sendFailure(Component.literal("Lag accounting is off: /mtmc lag on"));
            return 0;
        }
        List<Entry> worst = worst(src.getServer(), n);
        double total = 0;
        for (ServerLevel level : src.getServer().getAllLevels()) total += ((LagAccess) level).mtmc$lag().totalAvgMs();
        double sum = total;
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Lag by chunk (avg ms per tick over ~5 s; all chunks together: %.2f ms/t). Hover for contents, click to teleport:", sum))
            .withStyle(ChatFormatting.GOLD), false);
        if (worst.isEmpty()) src.sendSuccess(() -> Component.literal("  nothing measured yet"), false);
        int i = 1;
        for (Entry e : worst) {
            int rank = i++;
            src.sendSuccess(() -> line(rank, e), false);
        }
        return worst.size();
    }

    private static int here(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        Vec3 p = src.getPosition();
        long pos = ChunkPos.pack(((int) Math.floor(p.x)) >> 4, ((int) Math.floor(p.z)) >> 4);
        LagTracker.ChunkLag c = ((LagAccess) level).mtmc$lag().get(pos);
        if (c == null) {
            src.sendSuccess(() -> Component.literal("Nothing measured in this chunk"), false);
            return 0;
        }
        src.sendSuccess(() -> line(0, new Entry(level, c)), false);
        return 1;
    }

    private static int toggle(CommandSourceStack src, boolean on) {
        Mtmc.config().lagAccounting = on;
        Mtmc.config().save();
        src.sendSuccess(() -> Component.literal("MultithreadMC lag accounting " + (on ? "on" : "off")), true);
        return 1;
    }

    private static int reset(CommandSourceStack src) {
        for (ServerLevel level : src.getServer().getAllLevels()) ((LagAccess) level).mtmc$lag().reset();
        src.sendSuccess(() -> Component.literal("MultithreadMC lag numbers cleared"), true);
        return 1;
    }

    private static int release(CommandSourceStack src) {
        for (ServerLevel level : src.getServer().getAllLevels()) ((LagAccess) level).mtmc$lag().releaseAll();
        src.sendSuccess(() -> Component.literal("All slowed chunks are back to full speed (they are slowed again if they stay over budget while throttling is on)"), true);
        return 1;
    }

    private static int throttleStatus(CommandSourceStack src) {
        var cfg = Mtmc.config();
        int n = 0;
        for (ServerLevel level : src.getServer().getAllLevels()) n += ((LagAccess) level).mtmc$lag().throttledChunks();
        int slowed = n;
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Lag throttle %s: chunk budget %.1f ms/t, while server MSPT >= %.0f, or always above %.1f ms/t; slowest 1 tick in %d. Slowed now: %d chunks",
            cfg.lagThrottle ? "ON" : "off", cfg.lagChunkBudgetMs, cfg.lagServerBusyMs, cfg.lagHardBudgetMs, 1 << cfg.lagMaxThrottle, slowed)), false);
        return slowed;
    }

    private static int setThrottle(CommandSourceStack src, boolean on) {
        Mtmc.config().lagThrottle = on;
        if (on) Mtmc.config().lagAccounting = true;
        Mtmc.config().save();
        src.sendSuccess(() -> Component.literal("MultithreadMC lag throttle " + (on ? "on" : "off (slowed chunks go back to full speed within a second)")), true);
        return 1;
    }

    /** budget = lagChunkBudgetMs, busy = lagServerBusyMs, hard = lagHardBudgetMs. */
    private static int setBudget(CommandSourceStack src, String which, double ms) {
        var cfg = Mtmc.config();
        switch (which) {
            case "budget" -> cfg.lagChunkBudgetMs = ms;
            case "busy" -> cfg.lagServerBusyMs = ms;
            default -> cfg.lagHardBudgetMs = ms;
        }
        cfg.save();
        return throttleStatus(src);
    }

    static MutableComponent line(int rank, Entry e) {
        LagTracker.ChunkLag c = e.lag();
        ServerLevel level = e.level();
        int cx = ChunkPos.getX(c.pos), cz = ChunkPos.getZ(c.pos);
        int bx = (cx << 4) + 8, bz = (cz << 4) + 8;
        String dim = level.dimension().identifier().getPath();
        StringBuilder parts = new StringBuilder();
        Integer[] order = new Integer[LagCategory.ALL.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(c.avgMs[b], c.avgMs[a]));
        for (int i : order) {
            if (c.avgMs[i] < 0.005) continue;
            if (!parts.isEmpty()) parts.append(", ");
            parts.append(String.format(Locale.ROOT, "%s %.2f (%.0f/t)", LagCategory.ALL[i].label, c.avgMs[i], c.avgCount[i]));
        }
        String near = nearestPlayer(level, bx, bz);
        String slowed = c.throttle > 0 ? " [SLOWED 1/" + (1 << c.throttle) + "]" : "";
        String text = String.format(Locale.ROOT, "%s%s [%d, %d] (x %d, z %d): %.2f ms/t (peak %.2f)%s - %s%s",
            rank > 0 ? "#" + rank + " " : "", dim, cx, cz, bx, bz, c.avgTotalMs, c.peakMs, slowed, parts, near);
        int y = level.hasChunk(cx, cz) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) + 1 : 128;
        String tp = String.format(Locale.ROOT, "/execute in %s run tp @s %d %d %d", level.dimension().identifier(), bx, y, bz);
        ChatFormatting colour = c.avgTotalMs >= 10 ? ChatFormatting.RED : c.avgTotalMs >= 2 ? ChatFormatting.YELLOW : ChatFormatting.WHITE;
        return Component.literal(text).withStyle(s -> s.withColor(colour)
            .withClickEvent(new ClickEvent.RunCommand(tp))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(contents(level, cx, cz) + "\nclick: " + tp))));
    }

    private static String nearestPlayer(ServerLevel level, int x, int z) {
        ServerPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            double d = (p.getX() - x) * (p.getX() - x) + (p.getZ() - z) * (p.getZ() - z);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best == null ? "" : String.format(Locale.ROOT, " - nearest player %s %.0f m", best.getGameProfile().name(), Math.sqrt(bestD));
    }

    /** What's in the chunk right now (entities by type), for the hover text. */
    private static String contents(ServerLevel level, int cx, int cz) {
        if (!level.hasChunk(cx, cz)) return "chunk not loaded";
        AABB box = new AABB(cx << 4, level.getMinY(), cz << 4, (cx << 4) + 16, level.getMaxY() + 1, (cz << 4) + 16);
        Map<String, Integer> byType = new HashMap<>();
        for (Entity e : level.getEntities((Entity) null, box, x -> true)) byType.merge(e.getType().toShortString(), 1, Integer::sum);
        int blockEntities = level.getChunk(cx, cz).getBlockEntities().size();
        StringBuilder sb = new StringBuilder("entities: ");
        byType.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(6)
            .forEach(en -> sb.append(en.getValue()).append(' ').append(en.getKey()).append(", "));
        if (byType.isEmpty()) sb.append("none, ");
        sb.append(blockEntities).append(" block entities");
        return sb.toString();
    }
}
