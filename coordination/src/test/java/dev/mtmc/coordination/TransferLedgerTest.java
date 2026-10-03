package dev.mtmc.coordination;

import com.google.protobuf.ByteString;
import dev.mtmc.coordination.protocol.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Control-plane tests only. Opaque fixture bytes are not Minecraft/mod-state coverage. */
class TransferLedgerTest {
    @TempDir Path dir;
    static final Set<String> NODES = Set.of("lobby", "a", "b", "other");
    static final String PLAYER = "11111111-1111-1111-1111-111111111111";
    static final String TRANSFER = "22222222-2222-2222-2222-222222222222";
    static final byte[] SNAPSHOT = "opaque-control-plane-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static TransferCommand command(Step step) throws Exception {
        var b = TransferCommand.newBuilder().setVersion(1).setOperation(UUID.randomUUID().toString())
            .setTransfer(TRANSFER).setPlayer(PLAYER).setSource("a").setDestination("b").setEpoch(1).setStep(step);
        if (step == Step.QUIESCE) b.setSnapshot(ByteString.copyFrom(SNAPSHOT));
        if (step == Step.QUIESCE || step == Step.PREPARE) b.setDigest(TransferLedger.digest(SNAPSHOT));
        return b.build();
    }
    static TransferLedger open(Path file) throws Exception { return new TransferLedger(file, "lobby", NODES); }
    static void admit(TransferLedger l) throws Exception {
        l.admit("lobby", Admission.newBuilder().setVersion(1).setPlayer(PLAYER).setNode("a").build());
    }
    static Owner owner(TransferLedger l) throws Exception {
        return l.lookup("lobby", PlayerKey.newBuilder().setVersion(1).setPlayer(PLAYER).build());
    }
    static String peer(Step s) { return switch(s) { case PREPARE, ACTIVATE -> "b"; case COMMIT -> "lobby"; default -> "a"; }; }
    @Test void committedReceiptAndSnapshotSurviveRestartAndReplay() throws Exception {
        Path file = dir.resolve("ledger.db");
        TransferCommand commit = command(Step.COMMIT);
        TransferResult committed;
        try (var l = open(file)) {
            admit(l);
            for (Step s : List.of(Step.REQUEST, Step.QUIESCE, Step.PREPARE)) l.advance(peer(s), command(s));
            committed = l.advance("lobby", commit);
        }
        try (var l = open(file)) {
            assertEquals(committed, l.advance("lobby", commit));
            assertEquals("b", owner(l).getNode()); assertEquals(2, owner(l).getEpoch()); assertTrue(owner(l).getFrozen());
            var snapshot = l.inspect("b", TransferKey.newBuilder().setVersion(1).setTransfer(TRANSFER).build());
            assertArrayEquals(SNAPSHOT, snapshot.getSnapshot().toByteArray());
            l.advance("b", command(Step.ACTIVATE));
            assertFalse(owner(l).getFrozen());
            assertThrows(IllegalArgumentException.class, () -> l.advance("a", command(Step.ABORT)));
            assertThrows(IllegalArgumentException.class, () -> l.advance("a", command(Step.REQUEST).toBuilder().setTransfer(UUID.randomUUID().toString()).build()));
            l.advance("a", command(Step.CLEANUP));
        }
    }
    @Test void invalidRequestsRollbackWithoutAdvancingOwnership() throws Exception {
        try (var l = open(dir.resolve("ledger.db"))) {
            admit(l); l.advance("a", command(Step.REQUEST));
            assertThrows(IllegalArgumentException.class, () -> l.advance("a", command(Step.QUIESCE).toBuilder().setDigest("wrong").build()));
            assertFalse(owner(l).getFrozen());
            assertThrows(IllegalArgumentException.class, () -> l.advance("lobby", command(Step.COMMIT)));
            l.advance("a", command(Step.QUIESCE));
            assertThrows(IllegalArgumentException.class, () -> l.advance("other", command(Step.PREPARE)));
            assertThrows(IllegalArgumentException.class, () -> l.advance("b", command(Step.PREPARE).toBuilder().setEpoch(99).build()));
            l.advance("b", command(Step.PREPARE)); l.advance("a", command(Step.ABORT));
            assertEquals("a", owner(l).getNode()); assertFalse(owner(l).getFrozen());
        }
    }
    @Test void conflictingOperationIdCannotChangeCommandOrPeer() throws Exception {
        try (var l = open(dir.resolve("ledger.db"))) {
            admit(l); var request = command(Step.REQUEST); var result = l.advance("a", request);
            assertEquals(result, l.advance("a", request));
            assertThrows(IllegalArgumentException.class, () -> l.advance("a", request.toBuilder().setDestination("other").build()));
            assertThrows(IllegalArgumentException.class, () -> l.advance("b", request));
            assertEquals("a", owner(l).getNode());
        }
    }
    @Test void reconnectAndOtherPeersCannotThawOrReadPendingSnapshot() throws Exception {
        try (var l = open(dir.resolve("ledger.db"))) {
            admit(l); l.advance("a", command(Step.REQUEST)); l.advance("a", command(Step.QUIESCE));
            assertTrue(l.admit("lobby", Admission.newBuilder().setVersion(1).setPlayer(PLAYER).setNode("b").build()).getFrozen());
            assertThrows(IllegalArgumentException.class, () -> l.inspect("other", TransferKey.newBuilder().setVersion(1).setTransfer(TRANSFER).build()));
            assertThrows(IllegalArgumentException.class, () -> l.admit("a", Admission.newBuilder().setVersion(1).setPlayer(PLAYER).setNode("a").build()));
            assertThrows(IllegalArgumentException.class, () -> l.lookup("unknown", PlayerKey.newBuilder().setVersion(1).setPlayer(PLAYER).build()));
            assertThrows(IllegalArgumentException.class, () -> l.lookup("a", PlayerKey.newBuilder().setVersion(2).setPlayer(PLAYER).build()));
        }
    }
    @Test void competingRequestsHaveExactlyOneWinner() throws Exception {
        try (var l = open(dir.resolve("ledger.db")); var pool = Executors.newFixedThreadPool(8)) {
            admit(l); var start = new CountDownLatch(1);
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int i = 0; i < 16; i++) outcomes.add(pool.submit(() -> {
                start.await();
                try { l.advance("a", command(Step.REQUEST).toBuilder().setTransfer(UUID.randomUUID().toString()).build()); return true; }
                catch (java.sql.SQLException e) { return false; }
            }));
            start.countDown(); int winners = 0;
            for (var outcome : outcomes) if (outcome.get()) winners++;
            assertEquals(1, winners); assertEquals(1, owner(l).getEpoch());
        }
    }
    @Test void processDeathAfterEveryDurablePhasePreservesOwnerAndSnapshot() throws Exception {
        // Abrupt JVM halt, not close/reopen pretending to be a crash.
        for (int phase = 0; phase < 7; phase++) {
            Path file = dir.resolve("crash-" + phase + ".db");
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("mtmc.test.classpath"), CrashProbe.class.getName(), file.toString(), Integer.toString(phase))
                .redirectErrorStream(true).redirectOutput(dir.resolve("crash-" + phase + ".log").toFile()).start();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Crash probe timed out");
            assertEquals(73, process.exitValue());
            try (var l = open(file)) {
                assertEquals(phase >= 4 ? "b" : "a", owner(l).getNode());
                assertEquals(phase >= 4 ? 2 : 1, owner(l).getEpoch());
                assertEquals(phase >= 2 && phase <= 4, owner(l).getFrozen());
                if (phase >= 2) assertArrayEquals(SNAPSHOT, l.inspect("b", TransferKey.newBuilder().setVersion(1).setTransfer(TRANSFER).build()).getSnapshot().toByteArray());
            }
        }
    }
    public static final class CrashProbe {
        public static void main(String[] args) throws Exception {
            var l = open(Path.of(args[0])); admit(l);
            var steps = List.of(Step.REQUEST, Step.QUIESCE, Step.PREPARE, Step.COMMIT, Step.ACTIVATE, Step.CLEANUP);
            for (int i = 0; i < Integer.parseInt(args[1]); i++) l.advance(peer(steps.get(i)), command(steps.get(i)));
            Runtime.getRuntime().halt(73);
        }
    }
}
