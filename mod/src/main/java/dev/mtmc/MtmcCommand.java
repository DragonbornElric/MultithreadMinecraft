package dev.mtmc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /mtmc status|on|off|threads <n>|selftest [result]|lag ...} (admins). Changes apply from the next tick and are
 * written to the config file.
 */
public final class MtmcCommand {
    private MtmcCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mtmc")
            .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
            .executes(c -> status(c.getSource()))
            .then(Commands.literal("status").executes(c -> status(c.getSource())))
            .then(Commands.literal("cluster").executes(c -> {
                c.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(dev.mtmc.cluster.ClusterReadiness.status()), false);
                return 1;
            }))
            .then(Commands.literal("on").executes(c -> setParallel(c.getSource(), true)))
            .then(Commands.literal("off").executes(c -> setParallel(c.getSource(), false)))
            .then(dev.mtmc.lag.LagCommand.node())
            .then(Commands.literal("sensors")
                .executes(c -> sensors(c.getSource()))
                .then(Commands.literal("on").executes(c -> setSensors(c.getSource(), true)))
                .then(Commands.literal("off").executes(c -> setSensors(c.getSource(), false)))
                .then(Commands.literal("threads").then(Commands.argument("count", IntegerArgumentType.integer(0, 256))
                    .executes(c -> { Mtmc.config().sensorThreads = IntegerArgumentType.getInteger(c, "count"); Mtmc.config().save(); return sensors(c.getSource()); })))
                .then(Commands.literal("min").then(Commands.argument("mobs", IntegerArgumentType.integer(1, 100000))
                    .executes(c -> { Mtmc.config().sensorPhaseMin = IntegerArgumentType.getInteger(c, "mobs"); Mtmc.config().save(); return sensors(c.getSource()); }))))
            .then(Commands.literal("regions")
                .executes(c -> regions(c.getSource()))
                .then(Commands.literal("on").executes(c -> setRegions(c.getSource(), true)))
                .then(Commands.literal("off").executes(c -> setRegions(c.getSource(), false)))
                .then(Commands.literal("reset").executes(c -> { dev.mtmc.region.RegionStats.reset(); return regions(c.getSource()); }))
                .then(Commands.literal("threads").then(Commands.argument("count", IntegerArgumentType.integer(0, 256))
                    .executes(c -> { Mtmc.config().regionThreads = IntegerArgumentType.getInteger(c, "count"); Mtmc.config().save(); return regions(c.getSource()); })))
                .then(Commands.literal("cell").then(Commands.argument("chunks", IntegerArgumentType.integer(1, 64))
                    .executes(c -> { Mtmc.config().regionCellChunks = IntegerArgumentType.getInteger(c, "chunks"); Mtmc.config().save(); return regions(c.getSource()); })))
                .then(Commands.literal("min").then(Commands.argument("entities", IntegerArgumentType.integer(0, 1000000))
                    .executes(c -> { Mtmc.config().regionMinEntities = IntegerArgumentType.getInteger(c, "entities"); Mtmc.config().save(); return regions(c.getSource()); }))))
            .then(Commands.literal("selftest")
                .executes(c -> selfTest(c.getSource()))
                .then(Commands.literal("result").executes(c -> selfTestResult(c.getSource()))))
            .then(Commands.literal("threads")
                .executes(c -> status(c.getSource()))
                .then(Commands.argument("count", IntegerArgumentType.integer(0, 256))
                    .executes(c -> setThreads(c.getSource(), IntegerArgumentType.getInteger(c, "count"))))));
    }

    private static int status(CommandSourceStack src) {
        MtmcConfig cfg = Mtmc.config();
        int levels = 0;
        for (var ignored : src.getServer().getAllLevels()) levels++;
        int effective = ParallelLevelTicker.effectiveThreads(levels);
        String mode = !cfg.parallelDimensions ? "off (vanilla)"
            : effective < 2 ? "on, but 1 thread: levels tick on the server thread"
            : "on, " + effective + " worker threads for " + levels + " dimensions";
        String line = "MultithreadMC: " + mode + " (threads setting: " + (cfg.threads <= 0 ? "0 = one per dimension" : cfg.threads) + ")";
        src.sendSuccess(() -> Component.literal(line), false);
        String stats = MtmcStats.last();
        src.sendSuccess(() -> Component.literal("last stats: " + stats), false);
        return effective;
    }

    private static int selfTest(CommandSourceStack src) {
        ParallelLevelTicker.startSelfTest(20);
        src.sendSuccess(() -> Component.literal("MultithreadMC selftest: the next 20 parallel ticks load chunks across dimensions"
            + " from the workers; /mtmc selftest result"), false);
        return 1;
    }

    private static int selfTestResult(CommandSourceStack src) {
        String r = ParallelLevelTicker.selfTestResult();
        src.sendSuccess(() -> Component.literal(r), false);
        return 1;
    }

    private static int sensors(CommandSourceStack src) {
        var cfg = Mtmc.config();
        String line = String.format(java.util.Locale.ROOT,
            "Parallel sensor phase %s (threads %s, min %d mobs per level tick). So far: %d phases, %d mobs, %d sensors run early, %d fallbacks",
            cfg.sensorPhase ? "ON" : "off", cfg.sensorThreads <= 0 ? "CPUs-1" : String.valueOf(cfg.sensorThreads), cfg.sensorPhaseMin,
            dev.mtmc.ai.SensorPhase.PHASES.sum(), dev.mtmc.ai.SensorPhase.MOBS.sum(), dev.mtmc.ai.SensorPhase.SENSORS.sum(),
            dev.mtmc.ai.SensorPhase.FALLBACKS.sum());
        src.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int regions(CommandSourceStack src) {
        var cfg = Mtmc.config();
        String line = String.format(java.util.Locale.ROOT, "Region ticking %s (threads %s, cell %d chunks, min %d entities). So far: %s",
            cfg.regions ? "ON" : "off", cfg.regionThreads <= 0 ? "CPUs-1" : String.valueOf(cfg.regionThreads), cfg.regionCellChunks,
            cfg.regionMinEntities, MtmcStats.toJson(dev.mtmc.region.RegionStats.snapshot()));
        src.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int setRegions(CommandSourceStack src, boolean on) {
        Mtmc.config().regions = on;
        Mtmc.config().save();
        src.sendSuccess(() -> Component.literal("MultithreadMC region ticking " + (on ? "on" : "off") + " from the next tick"), true);
        return 1;
    }

    private static int setSensors(CommandSourceStack src, boolean on) {
        Mtmc.config().sensorPhase = on;
        Mtmc.config().save();
        src.sendSuccess(() -> Component.literal("MultithreadMC parallel sensor phase " + (on ? "on" : "off") + " from the next tick"), true);
        return 1;
    }

    private static int setParallel(CommandSourceStack src, boolean on) {
        MtmcConfig cfg = Mtmc.config();
        cfg.parallelDimensions = on;
        cfg.save();
        src.sendSuccess(() -> Component.literal("MultithreadMC parallel dimensions " + (on ? "on" : "off") + " from the next tick"), true);
        return 1;
    }

    private static int setThreads(CommandSourceStack src, int n) {
        MtmcConfig cfg = Mtmc.config();
        cfg.threads = n;
        cfg.save();
        src.sendSuccess(() -> Component.literal("MultithreadMC threads = " + (n == 0 ? "one per dimension" : n) + " from the next tick"), true);
        return n;
    }
}
