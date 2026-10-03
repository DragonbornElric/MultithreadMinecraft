package dev.mtmc.cluster;

/** Fail closed: a control-plane ACK is not evidence of a safe Minecraft state application. */
public final class ClusterReadiness {
    private ClusterReadiness() {}
    public static String status() {
        return "prototype control plane v2; gameplay BLOCKED: dimension bootstrap isolation, admission/fencing, "
            + "player/entity apply journal, async emma-smp and EndInv mutations are not integrated";
    }
    public static void requireReady() {
        if (Boolean.getBoolean("mtmc.cluster.enabled")) throw new IllegalStateException(status());
    }
}
