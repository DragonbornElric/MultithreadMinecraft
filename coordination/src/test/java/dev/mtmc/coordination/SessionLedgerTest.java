package dev.mtmc.coordination;

import dev.mtmc.coordination.protocol.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Ledger/session semantics only; does not claim Minecraft admission or a live-world crash test. */
class SessionLedgerTest {
    @TempDir Path dir;
    static SessionStart start(String boot, long previous) {
        return SessionStart.newBuilder().setVersion(TransferLedger.VERSION).setBoot(boot).setPreviousGeneration(previous).build();
    }
    static AdmissionCheck check(String boot, long generation, long epoch) {
        return AdmissionCheck.newBuilder().setVersion(TransferLedger.VERSION).setPlayer(TransferLedgerTest.PLAYER).setBoot(boot).setGeneration(generation).setEpoch(epoch).build();
    }
    @Test void newBootFencesOldMutationsIncludingDurableReceiptReplay() throws Exception {
        try (var l = TransferLedgerTest.open(dir.resolve("ledger.db"))) {
            TransferLedgerTest.admit(l);
            var command = TransferLedgerTest.command(Step.REQUEST);
            l.advance("a", command);
            String next = UUID.randomUUID().toString();
            assertEquals(2, l.openSession("a", start(next, 1)).getGeneration());
            assertThrows(IllegalArgumentException.class, () -> l.advance("a", command));
            assertThrows(IllegalArgumentException.class, () -> l.openSession("a", start(TransferLedgerTest.boot("a"), 0)));
            assertEquals("REQUESTED", l.inspect("a", TransferKey.newBuilder().setVersion(TransferLedger.VERSION).setTransfer(command.getTransfer()).build()).getState());
            // New boot reconciles the existing transfer and uses a new operation/session to continue.
            assertEquals("QUIESCED", l.advance("a", TransferLedgerTest.command(Step.QUIESCE).toBuilder().setBoot(next).setGeneration(2).build()).getState());
        }
    }
    @Test void delayedSessionStartsCannotTakeOverAndAckReplayIsIdempotent() throws Exception {
        try (var l = TransferLedgerTest.open(dir.resolve("ledger.db"))) {
            String one = UUID.randomUUID().toString(), two = UUID.randomUUID().toString();
            var first = l.openSession("a", start(one, 0));
            assertEquals(first, l.openSession("a", start(one, 0)));
            assertThrows(IllegalArgumentException.class, () -> l.openSession("a", start(one, 1)));
            var second = l.openSession("a", start(two, 1));
            assertThrows(IllegalArgumentException.class, () -> l.openSession("a", start(UUID.randomUUID().toString(), 0)));
            assertThrows(IllegalArgumentException.class, () -> l.openSession("a", start(one, 0)));
            assertEquals(second, l.getSession("a", SessionQuery.newBuilder().setVersion(TransferLedger.VERSION).build()));
        }
    }
    @Test void admissionRequiresCurrentSessionUnfrozenOwnerAndExactEpoch() throws Exception {
        try (var l = TransferLedgerTest.open(dir.resolve("ledger.db"))) {
            TransferLedgerTest.admit(l);
            var request = check(TransferLedgerTest.boot("a"), 1, 1);
            assertEquals("a", l.checkAdmission("a", request).getOwner().getNode());
            assertThrows(IllegalArgumentException.class, () -> l.checkAdmission("a", request.toBuilder().setEpoch(2).build()));
            assertThrows(IllegalArgumentException.class, () -> l.checkAdmission("b", check(TransferLedgerTest.boot("b"), 1, 1)));
            l.advance("a", TransferLedgerTest.command(Step.REQUEST));
            l.advance("a", TransferLedgerTest.command(Step.QUIESCE));
            assertThrows(IllegalArgumentException.class, () -> l.checkAdmission("a", request));
            String newLobby = UUID.randomUUID().toString();
            l.openSession("lobby", start(newLobby, 1));
            assertThrows(IllegalArgumentException.class, () -> l.admit("lobby", Admission.newBuilder().setVersion(TransferLedger.VERSION).setPlayer(TransferLedgerTest.PLAYER).setNode("a").setBoot(TransferLedgerTest.boot("lobby")).setGeneration(1).build()));
        }
    }
    @Test void racingBootsHaveOneGenerationWinner() throws Exception {
        try (var l = TransferLedgerTest.open(dir.resolve("ledger.db")); var pool = Executors.newFixedThreadPool(8)) {
            l.openSession("a", start(UUID.randomUUID().toString(), 0));
            var barrier = new CountDownLatch(1);
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 16; i++) futures.add(pool.submit(() -> {
                barrier.await();
                try { l.openSession("a", start(UUID.randomUUID().toString(), 1)); return true; }
                catch (IllegalArgumentException e) { return false; }
            }));
            barrier.countDown(); int winners = 0;
            for (var future : futures) if (future.get()) winners++;
            assertEquals(1, winners);
        }
    }
    @Test void abruptProcessDeathPreservesCurrentAndRetiredSessions() throws Exception {
        Path file = dir.resolve("session-crash.db");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("mtmc.test.classpath"), SessionCrash.class.getName(), file.toString())
            .redirectErrorStream(true).redirectOutput(dir.resolve("crash.log").toFile()).start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(73, process.exitValue());
        try (var l = TransferLedgerTest.open(file)) {
            assertEquals(2, l.getSession("a", SessionQuery.newBuilder().setVersion(TransferLedger.VERSION).build()).getGeneration());
            assertThrows(IllegalArgumentException.class, () -> l.openSession("a", start(TransferLedgerTest.boot("a"), 0)));
            assertThrows(IllegalArgumentException.class, () -> l.advance("a", TransferLedgerTest.command(Step.REQUEST)));
        }
    }
    public static class SessionCrash {
        public static void main(String[] args) throws Exception {
            var ledger = TransferLedgerTest.open(Path.of(args[0])); TransferLedgerTest.admit(ledger);
            ledger.openSession("a", start(UUID.randomUUID().toString(), 1)); Runtime.getRuntime().halt(73);
        }
    }
}
