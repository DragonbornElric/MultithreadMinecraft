package dev.mtmc.coordination;

import dev.mtmc.coordination.protocol.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Single-writer durable control plane. It does not assert that Minecraft has applied a snapshot. */
public final class TransferLedger implements AutoCloseable {
    public static final int VERSION = 1;
    public static final int MAX_SNAPSHOT = 8 * 1024 * 1024;
    private final Connection db;
    private final Set<String> nodes;
    private final String lobby;

    public TransferLedger(Path file, String lobby, Set<String> nodes) throws Exception {
        if (!nodes.contains(lobby)) throw new IllegalArgumentException("Lobby is not a cluster node");
        this.nodes = Set.copyOf(nodes);
        this.lobby = lobby;
        Files.createDirectories(file.toAbsolutePath().getParent());
        db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS owners(player TEXT PRIMARY KEY,node TEXT NOT NULL,epoch INTEGER NOT NULL CHECK(epoch>0),frozen INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS transfers(id TEXT PRIMARY KEY,player TEXT NOT NULL REFERENCES owners(player),source TEXT NOT NULL,destination TEXT NOT NULL,epoch INTEGER NOT NULL,state TEXT NOT NULL,snapshot BLOB,digest TEXT)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_transfer ON transfers(player) WHERE state NOT IN ('CLEANED','ABORTED')");
            s.execute("CREATE TABLE IF NOT EXISTS receipts(op TEXT PRIMARY KEY,peer TEXT NOT NULL,request BLOB NOT NULL,result BLOB NOT NULL)");
        }
    }

    public synchronized Owner admit(String peer, Admission request) throws Exception {
        peer(peer); version(request.getVersion()); uuid(request.getPlayer()); node(request.getNode());
        require(peer.equals(lobby), "Only lobby can admit");
        // Admission is insert-only. Reconnect never overwrites ownership or thaws a pending transfer.
        try (PreparedStatement p = db.prepareStatement("INSERT OR IGNORE INTO owners VALUES(?,?,1,0)")) {
            p.setString(1, request.getPlayer()); p.setString(2, request.getNode()); p.executeUpdate();
        }
        return owner(request.getPlayer());
    }

    public synchronized Owner lookup(String peer, PlayerKey request) throws Exception {
        peer(peer); version(request.getVersion()); uuid(request.getPlayer());
        return owner(request.getPlayer());
    }

    public synchronized TransferSnapshot inspect(String peer, TransferKey request) throws Exception {
        peer(peer); version(request.getVersion()); uuid(request.getTransfer());
        try (PreparedStatement p = db.prepareStatement("SELECT player,source,destination,epoch,state,snapshot,digest FROM transfers WHERE id=?")) {
            p.setString(1, request.getTransfer());
            try (ResultSet r = p.executeQuery()) {
                require(r.next(), "Unknown transfer");
                require(peer.equals(lobby) || peer.equals(r.getString(2)) || peer.equals(r.getString(3)), "Not a transfer participant");
                return TransferSnapshot.newBuilder().setPlayer(r.getString(1)).setSource(r.getString(2)).setDestination(r.getString(3))
                    .setEpoch(r.getLong(4)).setState(r.getString(5))
                    .setSnapshot(com.google.protobuf.ByteString.copyFrom(Objects.requireNonNullElseGet(r.getBytes(6), () -> new byte[0])))
                    .setDigest(Objects.requireNonNullElse(r.getString(7), "")).build();
            }
        }
    }

    public synchronized TransferResult advance(String peer, TransferCommand c) throws Exception {
        peer(peer); version(c.getVersion()); uuid(c.getOperation()); uuid(c.getTransfer()); uuid(c.getPlayer());
        node(c.getSource()); node(c.getDestination());
        require(!c.getSource().equals(c.getDestination()), "Source and destination must differ");
        require(c.getEpoch() > 0, "Invalid epoch");
        require(c.getSnapshot().size() <= MAX_SNAPSHOT, "Snapshot too large");
        require(c.getStep() != Step.UNRECOGNIZED, "Unknown step");
        require(c.getStep() == Step.QUIESCE || c.getSnapshot().isEmpty(), "Snapshot only allowed at quiesce");
        db.setAutoCommit(false);
        try {
            try (PreparedStatement p = db.prepareStatement("SELECT peer,request,result FROM receipts WHERE op=?")) {
                p.setString(1, c.getOperation());
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) {
                        require(peer.equals(r.getString(1)) && Arrays.equals(c.toByteArray(), r.getBytes(2)), "Operation ID reused with different command/peer");
                        TransferResult result = TransferResult.parseFrom(r.getBytes(3));
                        db.commit(); return result;
                    }
                }
            }
            Owner owner = owner(c.getPlayer());
            String state;
            String digest = "";
            if (c.getStep() == Step.REQUEST) {
                require(peer.equals(c.getSource()), "Only source requests transfer");
                require(owner.getNode().equals(peer) && owner.getEpoch() == c.getEpoch() && !owner.getFrozen(), "Stale or frozen owner");
                try (PreparedStatement p = db.prepareStatement("INSERT INTO transfers(id,player,source,destination,epoch,state) VALUES(?,?,?,?,?,'REQUESTED')")) {
                    p.setString(1, c.getTransfer()); p.setString(2, c.getPlayer()); p.setString(3, c.getSource());
                    p.setString(4, c.getDestination()); p.setLong(5, c.getEpoch()); p.executeUpdate();
                }
                state = "REQUESTED";
            } else {
                String old;
                try (PreparedStatement p = db.prepareStatement("SELECT player,source,destination,epoch,state,digest FROM transfers WHERE id=?")) {
                    p.setString(1, c.getTransfer());
                    try (ResultSet r = p.executeQuery()) {
                        require(r.next(), "Unknown transfer");
                        require(c.getPlayer().equals(r.getString(1)) && c.getSource().equals(r.getString(2)) && c.getDestination().equals(r.getString(3)) && c.getEpoch() == r.getLong(4), "Transfer identity mismatch");
                        old = r.getString(5); digest = Objects.requireNonNullElse(r.getString(6), "");
                    }
                }
                state = switch (c.getStep()) {
                    case QUIESCE -> {
                        require(peer.equals(c.getSource()) && old.equals("REQUESTED"), "Quiesce requires source/requested");
                        require(!c.getSnapshot().isEmpty(), "Empty snapshot");
                        digest = digest(c.getSnapshot().toByteArray());
                        require(digest.equals(c.getDigest()), "Snapshot digest mismatch");
                        try (PreparedStatement p = db.prepareStatement("UPDATE transfers SET snapshot=?,digest=? WHERE id=?")) {
                            p.setBytes(1, c.getSnapshot().toByteArray()); p.setString(2, digest); p.setString(3, c.getTransfer()); p.executeUpdate();
                        }
                        updateOwner(c.getPlayer(), c.getSource(), c.getEpoch(), true);
                        yield "QUIESCED";
                    }
                    case PREPARE -> {
                        require(peer.equals(c.getDestination()) && old.equals("QUIESCED"), "Prepare requires destination/quiesced");
                        require(digest.equals(c.getDigest()), "Prepared snapshot digest mismatch");
                        yield "PREPARED";
                    }
                    case COMMIT -> {
                        require(peer.equals(lobby) && old.equals("PREPARED"), "Commit requires authority/prepared");
                        require(owner.getNode().equals(c.getSource()) && owner.getEpoch() == c.getEpoch() && owner.getFrozen(), "Ownership CAS failed");
                        updateOwner(c.getPlayer(), c.getDestination(), Math.addExact(c.getEpoch(), 1), true);
                        yield "COMMITTED";
                    }
                    case ACTIVATE -> {
                        require(peer.equals(c.getDestination()) && old.equals("COMMITTED"), "Activate requires committed destination");
                        require(owner.getNode().equals(peer) && owner.getEpoch() == Math.addExact(c.getEpoch(), 1) && owner.getFrozen(), "Destination epoch mismatch");
                        updateOwner(c.getPlayer(), peer, owner.getEpoch(), false);
                        yield "ACTIVE";
                    }
                    case CLEANUP -> {
                        require(peer.equals(c.getSource()) && old.equals("ACTIVE"), "Cleanup requires source/active");
                        yield "CLEANED";
                    }
                    case ABORT -> {
                        require(peer.equals(c.getSource()) && Set.of("REQUESTED", "QUIESCED", "PREPARED").contains(old), "Only source can abort before commit");
                        require(owner.getNode().equals(peer) && owner.getEpoch() == c.getEpoch(), "Abort owner mismatch");
                        updateOwner(c.getPlayer(), peer, c.getEpoch(), false);
                        yield "ABORTED";
                    }
                    default -> throw new IllegalArgumentException("Invalid transition");
                };
                try (PreparedStatement p = db.prepareStatement("UPDATE transfers SET state=? WHERE id=?")) {
                    p.setString(1, state); p.setString(2, c.getTransfer()); p.executeUpdate();
                }
            }
            TransferResult result = TransferResult.newBuilder().setState(state).setOwner(owner(c.getPlayer())).setDigest(digest).build();
            try (PreparedStatement p = db.prepareStatement("INSERT INTO receipts VALUES(?,?,?,?)")) {
                p.setString(1, c.getOperation()); p.setString(2, peer); p.setBytes(3, c.toByteArray()); p.setBytes(4, result.toByteArray()); p.executeUpdate();
            }
            db.commit(); // ACK only after durable owner, snapshot, phase and dedup receipt commit together.
            return result;
        } catch (Exception e) {
            db.rollback(); throw e;
        } finally { db.setAutoCommit(true); }
    }

    private Owner owner(String player) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT node,epoch,frozen FROM owners WHERE player=?")) {
            p.setString(1, player);
            try (ResultSet r = p.executeQuery()) {
                require(r.next(), "Unknown player");
                return Owner.newBuilder().setNode(r.getString(1)).setEpoch(r.getLong(2)).setFrozen(r.getBoolean(3)).build();
            }
        }
    }
    private void updateOwner(String player, String node, long epoch, boolean frozen) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("UPDATE owners SET node=?,epoch=?,frozen=? WHERE player=?")) {
            p.setString(1, node); p.setLong(2, epoch); p.setBoolean(3, frozen); p.setString(4, player); p.executeUpdate();
        }
    }
    private void peer(String peer) { node(peer); }
    private void node(String node) { require(nodes.contains(node), "Unknown peer/node"); }
    private static void version(int v) { require(v == VERSION, "Unsupported protocol version"); }
    private static void uuid(String value) { require(UUID.fromString(value).toString().equals(value), "Noncanonical UUID"); }
    private static void require(boolean ok, String reason) { if (!ok) throw new IllegalArgumentException(reason); }
    public static String digest(byte[] bytes) throws NoSuchAlgorithmException { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    @Override public synchronized void close() throws SQLException { db.close(); }
}
