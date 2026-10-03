package dev.mtmc.coordination;

import dev.mtmc.coordination.protocol.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Node-local durable commands. The row is retained when a connection/ACK is lost. */
public final class DurableOutbox implements AutoCloseable {
    private final Connection db;
    public DurableOutbox(Path file, String cluster, String node) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL");
            s.execute("CREATE TABLE IF NOT EXISTS binding(cluster TEXT NOT NULL,node TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS outbox(op TEXT PRIMARY KEY,command BLOB NOT NULL,result BLOB)");
        }
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT cluster,node FROM binding")) {
            if (r.next()) {
                if (!cluster.equals(r.getString(1)) || !node.equals(r.getString(2))) { db.close(); throw new IllegalArgumentException("Outbox belongs to another cluster/node"); }
                return;
            }
        }
        try (PreparedStatement p = db.prepareStatement("INSERT INTO binding VALUES(?,?)")) {
            p.setString(1, cluster); p.setString(2, node); p.executeUpdate();
        }
    }
    public synchronized Optional<TransferResult> enqueue(TransferCommand c) throws Exception {
        if (c.getSnapshot().size() > TransferLedger.MAX_SNAPSHOT) throw new IllegalArgumentException("Snapshot too large");
        if (c.getVersion() != TransferLedger.VERSION) throw new IllegalArgumentException("Unsupported version");
        UUID.fromString(c.getOperation());
        try (PreparedStatement p = db.prepareStatement("SELECT command,result FROM outbox WHERE op=?")) {
            p.setString(1, c.getOperation());
            try (ResultSet r = p.executeQuery()) {
                if (r.next()) {
                    if (!Arrays.equals(c.toByteArray(), r.getBytes(1))) throw new IllegalArgumentException("Outbox operation ID reused");
                    byte[] bytes = r.getBytes(2);
                    return bytes == null ? Optional.empty() : Optional.of(TransferResult.parseFrom(bytes));
                }
            }
        }
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM outbox WHERE result IS NULL")) {
            if (r.next() && r.getInt(1) >= 32) throw new IllegalStateException("Durable outbox full; stop accepting critical operations");
        }
        try (PreparedStatement p = db.prepareStatement("INSERT INTO outbox(op,command) VALUES(?,?)")) {
            p.setString(1, c.getOperation()); p.setBytes(2, c.toByteArray()); p.executeUpdate();
        }
        return Optional.empty();
    }
    public synchronized void acknowledge(String op, TransferResult result) throws Exception {
        try (PreparedStatement p = db.prepareStatement("SELECT result FROM outbox WHERE op=?")) {
            p.setString(1, op);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("ACK for unknown operation");
                byte[] old = r.getBytes(1);
                if (old != null && !Arrays.equals(old, result.toByteArray())) throw new IllegalArgumentException("Conflicting durable ACK");
            }
        }
        try (PreparedStatement p = db.prepareStatement("UPDATE outbox SET result=? WHERE op=?")) {
            p.setBytes(1, result.toByteArray()); p.setString(2, op); p.executeUpdate();
        }
    }
    public synchronized List<TransferCommand> pending() throws Exception {
        List<TransferCommand> commands = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT command FROM outbox WHERE result IS NULL ORDER BY rowid LIMIT 33")) {
            while (r.next()) commands.add(TransferCommand.parseFrom(r.getBytes(1)));
        }
        if (commands.size() > 32) throw new IllegalStateException("Outbox exceeds configured recovery bound");
        return commands;
    }
    @Override public synchronized void close() throws SQLException { db.close(); }
}
