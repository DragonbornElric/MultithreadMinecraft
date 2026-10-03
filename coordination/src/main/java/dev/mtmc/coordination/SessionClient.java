package dev.mtmc.coordination;

import dev.mtmc.coordination.protocol.*;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.stub.StreamObserver;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;

/** Off-tick construction/close; bounded async session and admission diagnostics. Not a gameplay lease. */
public final class SessionClient implements AutoCloseable {
    private final String node, boot = UUID.randomUUID().toString();
    private final FileChannel lockFile;
    private final FileLock lock;
    private final ManagedChannel channel;
    private final AuthorityGrpc.AuthorityStub rpc;
    private final Semaphore capacity = new Semaphore(32);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<NodeSession> current = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<NodeSession>> starting = new AtomicReference<>();
    private final ConcurrentHashMap<CompletableFuture<?>, Boolean> active = new ConcurrentHashMap<>();

    public SessionClient(Path runtimeLock, String node, String host, int port, Path cert, Path key, Path ca) throws Exception {
        this.node = node;
        Files.createDirectories(runtimeLock.toAbsolutePath().getParent());
        lockFile = FileChannel.open(runtimeLock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = lockFile.tryLock();
            if (acquired == null) throw new IllegalStateException("Node runtime directory already in use");
        } catch (Exception e) { lockFile.close(); throw e; }
        lock = acquired;
        try {
            channel = NettyChannelBuilder.forAddress(host, port)
                .sslContext(GrpcSslContexts.forClient().trustManager(ca.toFile()).keyManager(cert.toFile(), key.toFile()).build()).build();
        } catch (Exception e) { lock.release(); lockFile.close(); throw e; }
        rpc = AuthorityGrpc.newStub(channel);
    }

    private <T> CompletableFuture<T> request(BiConsumer<AuthorityGrpc.AuthorityStub, StreamObserver<T>> call) {
        var future = new CompletableFuture<T>();
        if (closed.get() || !capacity.tryAcquire()) {
            future.completeExceptionally(new RejectedExecutionException("Session client closed or request bound reached")); return future;
        }
        active.put(future, true);
        future.whenComplete((v, e) -> { active.remove(future); capacity.release(); });
        if (closed.get()) { future.completeExceptionally(new CancellationException("Session client closed")); return future; }
        try {
            call.accept(rpc.withDeadlineAfter(5, TimeUnit.SECONDS), new StreamObserver<>() {
                private T value;
                @Override public void onNext(T response) { value = response; }
                @Override public void onError(Throwable error) { future.completeExceptionally(error); }
                @Override public void onCompleted() {
                    if (value == null) future.completeExceptionally(new IllegalStateException("Missing authority reply"));
                    else future.complete(value);
                }
            });
        } catch (Exception e) { future.completeExceptionally(e); }
        return future;
    }

    public CompletionStage<NodeSession> start() {
        var existing = starting.get();
        if (existing != null) return existing;
        var result = new CompletableFuture<NodeSession>();
        if (!starting.compareAndSet(null, result)) return starting.get();
        this.<NodeSession>request((s, o) -> s.getSession(SessionQuery.newBuilder().setVersion(TransferLedger.VERSION).build(), o))
            .thenCompose(previous -> this.<NodeSession>request((s, o) -> s.openSession(SessionStart.newBuilder()
                .setVersion(TransferLedger.VERSION).setBoot(boot)
                .setPreviousGeneration(previous.getBoot().equals(boot) ? previous.getGeneration()-1 : previous.getGeneration()).build(), o)))
            .whenComplete((session, error) -> {
                if (error == null && !closed.get() && session.getNode().equals(node) && session.getBoot().equals(boot) && session.getGeneration()>0) {
                    current.set(session); result.complete(session);
                } else {
                    result.completeExceptionally(error != null ? error : new IllegalStateException("Session identity mismatch or client closed"));
                    starting.compareAndSet(result, null); // Retry same boot UUID; authority handles ACK loss idempotently.
                }
            });
        return result;
    }

    public CompletionStage<AdmissionDecision> checkAdmission(String player, long epoch) {
        var session = current.get();
        if (session == null) return CompletableFuture.failedFuture(new IllegalStateException("Start node session before admission check"));
        return this.<AdmissionDecision>request((s, o) -> s.checkAdmission(AdmissionCheck.newBuilder()
            .setVersion(TransferLedger.VERSION).setPlayer(player).setEpoch(epoch)
            .setBoot(session.getBoot()).setGeneration(session.getGeneration()).build(), o))
            .thenApply(decision -> {
                if (closed.get() || !decision.getSession().equals(session) || !decision.getOwner().getNode().equals(node)
                    || decision.getOwner().getEpoch() != epoch || decision.getOwner().getFrozen()) throw new IllegalStateException("Admission response mismatch");
                return decision;
            });
    }

    @Override public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        current.set(null);
        active.keySet().forEach(f -> f.completeExceptionally(new CancellationException("Session client stopped")));
        channel.shutdownNow(); channel.awaitTermination(5, TimeUnit.SECONDS);
        lock.release(); lockFile.close();
    }
}
