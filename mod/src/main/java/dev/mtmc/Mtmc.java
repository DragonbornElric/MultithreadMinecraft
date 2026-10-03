package dev.mtmc;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Mtmc implements DedicatedServerModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("MultithreadMC");
    private static volatile MtmcConfig config;

    @Override
    public void onInitializeServer() {
        dev.mtmc.cluster.ClusterReadiness.requireReady();
        MtmcConfig cfg = config();
        LOGGER.info("MultithreadMC: parallelDimensions={} threads={} deferCommandBlocks={}", cfg.parallelDimensions,
            cfg.threads <= 0 ? "one per dimension" : cfg.threads, cfg.deferCommandBlocks);
    }

    public static MtmcConfig config() {
        MtmcConfig c = config;
        if (c == null) {
            synchronized (Mtmc.class) {
                if (config == null) {
                    config = MtmcConfig.load(FabricLoader.getInstance().getConfigDir().resolve("multithreadmc.properties"));
                }
                c = config;
            }
        }
        return c;
    }
}
