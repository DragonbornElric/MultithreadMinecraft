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
            .then(Commands.literal("on").executes(c -> setParallel(c.getSource(), true)))
            .then(Commands.literal("off").executes(c -> setParallel(c.getSource(), false)))
            .then(dev.mtmc.lag.LagCommand.node())
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
