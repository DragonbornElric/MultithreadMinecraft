package dev.mtmc.coordination;

import dev.mtmc.coordination.protocol.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DurableOutboxTest {
    @TempDir Path dir;
    @Test void pendingCommandSurvivesRestartAndConflictingAckCannotOverwriteReceipt() throws Exception {
        Path file = dir.resolve("outbox.db"); var c = TransferLedgerTest.command(Step.REQUEST);
        var result = TransferResult.newBuilder().setState("REQUESTED").build();
        try (var box = new DurableOutbox(file, "lab", "a")) { assertTrue(box.enqueue(c).isEmpty()); }
        try (var box = new DurableOutbox(file, "lab", "a")) {
            assertEquals(List.of(c), box.pending()); box.acknowledge(c.getOperation(), result);
            assertThrows(IllegalArgumentException.class, () -> box.acknowledge(c.getOperation(), result.toBuilder().setState("ACTIVE").build()));
        }
        try (var box = new DurableOutbox(file, "lab", "a")) {
            assertTrue(box.pending().isEmpty()); assertEquals(result, box.enqueue(c).orElseThrow());
            assertThrows(IllegalArgumentException.class, () -> box.enqueue(c.toBuilder().setDestination("other").build()));
        }
        assertThrows(IllegalArgumentException.class, () -> new DurableOutbox(file, "other-cluster", "a"));
    }
    @Test void persistedBackpressureSurvivesClientRestart() throws Exception {
        Path file = dir.resolve("outbox.db");
        try (var box = new DurableOutbox(file, "lab", "a")) {
            for (int i = 0; i < 32; i++) box.enqueue(TransferLedgerTest.command(Step.REQUEST));
        }
        try (var box = new DurableOutbox(file, "lab", "a")) {
            assertEquals(32, box.pending().size());
            assertThrows(IllegalStateException.class, () -> box.enqueue(TransferLedgerTest.command(Step.REQUEST)));
        }
    }
}
