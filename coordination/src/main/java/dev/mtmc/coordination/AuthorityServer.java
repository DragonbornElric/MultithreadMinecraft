package dev.mtmc.coordination;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Standalone control plane; no plaintext mode, Minecraft traffic, or tick-thread dependency. */
public final class AuthorityServer {
    public static void main(String[] args) throws Exception {
        if (args.length != 8) throw new IllegalArgumentException("Usage: db cluster lobby port server-cert server-key ca-cert node1,node2,...");
        String cluster = args[1], lobby = args[2];
        Set<String> nodes = Set.of(args[7].split(","));
        PeerIdentity identity = new PeerIdentity(cluster, nodes);
        AuthorityService service = new AuthorityService(new TransferLedger(Path.of(args[0]), cluster, lobby, nodes));
        Server server;
        try {
            server = NettyServerBuilder.forPort(Integer.parseInt(args[3]))
                .sslContext(GrpcSslContexts.forServer(Path.of(args[4]).toFile(), Path.of(args[5]).toFile())
                    .trustManager(Path.of(args[6]).toFile()).clientAuth(ClientAuth.REQUIRE).build())
                .maxInboundMessageSize(TransferLedger.MAX_SNAPSHOT + 65536)
                .maxConcurrentCallsPerConnection(32)
                .addService(service).intercept(identity).build().start();
        } catch (Exception e) { service.close(); throw e; }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.shutdown();
                if (!server.awaitTermination(10, TimeUnit.SECONDS)) server.shutdownNow();
                service.close();
            } catch (Exception e) { System.err.println("Authority shutdown incomplete: " + e.getClass().getSimpleName()); }
        }, "mtmc-authority-shutdown"));
        System.out.println("MTMC authority ready on peer port " + server.getPort());
        server.awaitTermination();
    }
}
