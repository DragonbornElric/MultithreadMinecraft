package dev.mtmc.coordination;

import dev.mtmc.coordination.protocol.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback TLS/gRPC; this is transport evidence, not multi-PC or Minecraft evidence. */
class TransportTest {
    @TempDir Path dir;
    private Server server;
    private AuthorityService service;
    private final List<ManagedChannel> channels = new ArrayList<>();
    @BeforeEach void start() throws Exception {
        var p = new ProcessBuilder("bash", System.getProperty("mtmc.pki.script"), dir.toString())
            .redirectErrorStream(true).redirectOutput(dir.resolve("pki.log").toFile()).start();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, p.exitValue());
        var nodes = Set.of("lobby", "a", "b");
        var ledger = new TransferLedger(dir.resolve("ledger.db"), "lobby", nodes);
        for (var node : nodes) ledger.openSession(node, SessionStart.newBuilder().setVersion(TransferLedger.VERSION).setBoot(TransferLedgerTest.boot(node)).build());
        service = new AuthorityService(ledger);
        server = NettyServerBuilder.forPort(0)
            .sslContext(GrpcSslContexts.forServer(dir.resolve("server.pem").toFile(), dir.resolve("server.key").toFile())
                .trustManager(dir.resolve("ca.pem").toFile()).clientAuth(ClientAuth.REQUIRE).build())
            .maxInboundMessageSize(TransferLedger.MAX_SNAPSHOT + 65536).maxConcurrentCallsPerConnection(32)
            .addService(service).intercept(new PeerIdentity("lab", nodes)).build().start();
    }
    private AuthorityGrpc.AuthorityBlockingStub client(String node) throws Exception {
        var channel = NettyChannelBuilder.forAddress("localhost", server.getPort())
            .sslContext(GrpcSslContexts.forClient().trustManager(dir.resolve("ca.pem").toFile())
                .keyManager(dir.resolve(node + ".pem").toFile(), dir.resolve(node + ".key").toFile()).build()).build();
        channels.add(channel);
        return AuthorityGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
    }
    private Admission admission() {
        return Admission.newBuilder().setVersion(TransferLedger.VERSION).setPlayer(TransferLedgerTest.PLAYER).setNode("a").setBoot(TransferLedgerTest.boot("lobby")).setGeneration(1).build();
    }
    @Test void authenticatedDistinctPeersTransferAndReplayOverNetwork() throws Exception {
        var lobby = client("lobby"); var source = client("a"); var dest = client("b");
        assertEquals("a", lobby.admit(admission()).getNode());
        var request = TransferLedgerTest.command(Step.REQUEST);
        assertEquals(source.advance(request), source.advance(request));
        source.advance(TransferLedgerTest.command(Step.QUIESCE));
        var recovered = dest.inspect(TransferKey.newBuilder().setVersion(TransferLedger.VERSION).setTransfer(TransferLedgerTest.TRANSFER).build());
        assertArrayEquals(TransferLedgerTest.SNAPSHOT, recovered.getSnapshot().toByteArray());
        dest.advance(TransferLedgerTest.command(Step.PREPARE));
        assertEquals("b", lobby.advance(TransferLedgerTest.command(Step.COMMIT)).getOwner().getNode());
        assertFalse(dest.advance(TransferLedgerTest.command(Step.ACTIVATE)).getOwner().getFrozen());
        source.advance(TransferLedgerTest.command(Step.CLEANUP));
    }
    @Test void trustedCaDoesNotAdmitUnknownNodeOrWrongCluster() throws Exception {
        for (String node : List.of("outsider", "wrongcluster")) {
            var e = assertThrows(StatusRuntimeException.class, () -> client(node).admit(admission()));
            assertEquals(Status.Code.UNAUTHENTICATED, e.getStatus().getCode());
        }
    }
    @Test void asynchronousPeerReplaysPersistedRequestAfterClientRestart() throws Exception {
        client("lobby").admit(admission());
        int port = server.getPort();
        var command = TransferLedgerTest.command(Step.REQUEST);
        Path file = dir.resolve("peer.db");
        // Persist a command before constructing a network client, representing a process crash before send.
        try (var outbox = new DurableOutbox(file, "lab", "a")) { outbox.enqueue(command); }
        try (var peer = new PeerClient(file, "lab", "a", "localhost", port, dir.resolve("a.pem"), dir.resolve("a.key"), dir.resolve("ca.pem"))) {
            assertEquals("REQUESTED", peer.submit(command).toCompletableFuture().get(10, TimeUnit.SECONDS).getState());
        }
        try (var outbox = new DurableOutbox(file, "lab", "a")) { assertTrue(outbox.pending().isEmpty()); }
        // ACK-loss analogue: same original persisted command delivered again returns same durable receipt.
        try (var peer = new PeerClient(file, "lab", "a", "localhost", port, dir.resolve("a.pem"), dir.resolve("a.key"), dir.resolve("ca.pem"))) {
            assertEquals("REQUESTED", peer.submit(command).toCompletableFuture().get(10, TimeUnit.SECONDS).getState());
        }
    }
    @Test void sourceCannotImpersonateLobbyOrCommit() throws Exception {
        client("lobby").admit(admission());
        var source = client("a");
        assertEquals(Status.Code.FAILED_PRECONDITION, assertThrows(StatusRuntimeException.class, () -> source.admit(admission())).getStatus().getCode());
        source.advance(TransferLedgerTest.command(Step.REQUEST)); source.advance(TransferLedgerTest.command(Step.QUIESCE));
        client("b").advance(TransferLedgerTest.command(Step.PREPARE));
        assertEquals(Status.Code.FAILED_PRECONDITION, assertThrows(StatusRuntimeException.class, () -> source.advance(TransferLedgerTest.command(Step.COMMIT))).getStatus().getCode());
    }
    @Test void incompatibleVersionAndMissingDeadlineRejected() throws Exception {
        var lobby = client("lobby");
        assertEquals(Status.Code.FAILED_PRECONDITION, assertThrows(StatusRuntimeException.class, () -> lobby.admit(admission().toBuilder().setVersion(999).build())).getStatus().getCode());
        assertEquals(Status.Code.UNAUTHENTICATED, assertThrows(StatusRuntimeException.class, () -> lobby.withDeadline(null).admit(admission())).getStatus().getCode());
    }
    private SessionClient sessionClient(Path lock) throws Exception {
        return new SessionClient(lock, "a", "localhost", server.getPort(), dir.resolve("a.pem"), dir.resolve("a.key"), dir.resolve("ca.pem"));
    }
    @Test void asyncSessionAdmissionRejectsRestartedSourceAndFrozenPlayer() throws Exception {
        client("lobby").admit(admission());
        try (var old = sessionClient(dir.resolve("old.lock"))) {
            var session = old.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(2, session.getGeneration());
            assertEquals("a", old.checkAdmission(TransferLedgerTest.PLAYER, 1).toCompletableFuture().get(10, TimeUnit.SECONDS).getOwner().getNode());
            try (var next = sessionClient(dir.resolve("next.lock"))) {
                var fresh = next.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(3, fresh.getGeneration());
                assertThrows(java.util.concurrent.ExecutionException.class, () -> old.checkAdmission(TransferLedgerTest.PLAYER, 1).toCompletableFuture().get(10, TimeUnit.SECONDS));
                assertEquals("a", next.checkAdmission(TransferLedgerTest.PLAYER, 1).toCompletableFuture().get(10, TimeUnit.SECONDS).getOwner().getNode());
                var source = client("a");
                source.advance(TransferLedgerTest.command(Step.REQUEST).toBuilder().setBoot(fresh.getBoot()).setGeneration(3).build());
                source.advance(TransferLedgerTest.command(Step.QUIESCE).toBuilder().setBoot(fresh.getBoot()).setGeneration(3).build());
                assertThrows(java.util.concurrent.ExecutionException.class, () -> next.checkAdmission(TransferLedgerTest.PLAYER, 1).toCompletableFuture().get(10, TimeUnit.SECONDS));
            }
        }
    }
    @Test void sameRuntimeDirectoryCannotStartTwoNodeClients() throws Exception {
        try (var first = sessionClient(dir.resolve("node.lock"))) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class, () -> sessionClient(dir.resolve("node.lock")));
        }
        try (var restarted = sessionClient(dir.resolve("node.lock"))) { assertEquals(2, restarted.start().toCompletableFuture().get(10, TimeUnit.SECONDS).getGeneration()); }
    }
    @Test void cachedTransferReceiptCannotActAsCurrentOwnerOrBootProof() throws Exception {
        client("lobby").admit(admission());
        var command = TransferLedgerTest.command(Step.REQUEST);
        Path file = dir.resolve("receipt-peer.db");
        try (var peer = new PeerClient(file, "lab", "a", "localhost", server.getPort(), dir.resolve("a.pem"), dir.resolve("a.key"), dir.resolve("ca.pem"))) {
            assertEquals("REQUESTED", peer.submit(command).toCompletableFuture().get(10, TimeUnit.SECONDS).getState());
            client("a").advance(TransferLedgerTest.command(Step.QUIESCE));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> peer.submit(command).toCompletableFuture().get(10, TimeUnit.SECONDS));
            try (var fresh = sessionClient(dir.resolve("new-boot.lock"))) {
                fresh.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertThrows(java.util.concurrent.ExecutionException.class, () -> peer.submit(command).toCompletableFuture().get(10, TimeUnit.SECONDS));
            }
        }
    }
    @Test void plaintextAndMissingClientCertificateCannotReachService() throws Exception {
        var plain = NettyChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        var anonymous = NettyChannelBuilder.forAddress("localhost", server.getPort())
            .sslContext(GrpcSslContexts.forClient().trustManager(dir.resolve("ca.pem").toFile()).build()).build();
        channels.add(plain); channels.add(anonymous);
        for (var channel : List.of(plain, anonymous)) {
            var error = assertThrows(StatusRuntimeException.class, () -> AuthorityGrpc.newBlockingStub(channel).withDeadlineAfter(3, TimeUnit.SECONDS).admit(admission()));
            assertNotEquals(Status.Code.OK, error.getStatus().getCode());
        }
    }
    @AfterEach void stop() throws Exception {
        for (var channel : channels) { channel.shutdownNow(); channel.awaitTermination(5, TimeUnit.SECONDS); }
        if (server != null) { server.shutdownNow(); server.awaitTermination(5, TimeUnit.SECONDS); }
        if (service != null) service.close();
    }
}
