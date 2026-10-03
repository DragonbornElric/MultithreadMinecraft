package dev.mtmc.coordination;

import dev.mtmc.coordination.protocol.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Async critical-command client. Never call join/get on its result from a Minecraft tick. */
public final class PeerClient implements AutoCloseable {
    private final DurableOutbox outbox;
    private final ManagedChannel channel;
    private final AuthorityGrpc.AuthorityStub rpc;
    private final ConcurrentHashMap<String, CompletableFuture<TransferResult>> active = new ConcurrentHashMap<>();
    private final Semaphore capacity = new Semaphore(32);
    private final ThreadPoolExecutor disk = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(64), Thread.ofPlatform().name("mtmc-outbox-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("mtmc-retry").factory());
    private final AtomicBoolean closed = new AtomicBoolean();

    public PeerClient(Path file, String cluster, String node, String host, int port, Path cert, Path key, Path ca) throws Exception {
        outbox = new DurableOutbox(file, cluster, node);
        try {
            channel = NettyChannelBuilder.forAddress(host, port).maxInboundMessageSize(TransferLedger.MAX_SNAPSHOT + 65536)
                .sslContext(GrpcSslContexts.forClient().trustManager(ca.toFile()).keyManager(cert.toFile(), key.toFile()).build()).build();
        } catch (Exception e) { outbox.close(); throw e; }
        rpc = AuthorityGrpc.newStub(channel);
        // Startup is off-tick: replay persisted commands using their original IDs.
        for (var command : outbox.pending()) submit(command);
    }
    public CompletionStage<TransferResult> submit(TransferCommand command) {
        var result = new CompletableFuture<TransferResult>();
        if (closed.get() || command.getSnapshot().size() > TransferLedger.MAX_SNAPSHOT || !capacity.tryAcquire()) {
            result.completeExceptionally(new RejectedExecutionException("Peer closed, oversized command, or in-flight bound reached")); return result;
        }
        var existing = active.putIfAbsent(command.getOperation(), result);
        if (existing != null) {
            capacity.release();
            // Verify duplicate payload durably before returning another operation's future.
            try { disk.execute(() -> {
                try { outbox.enqueue(command); existing.whenComplete((value, error) -> { if (error != null) result.completeExceptionally(error); else result.complete(value); }); }
                catch (Exception e) { result.completeExceptionally(e); }
            }); } catch (RejectedExecutionException e) { result.completeExceptionally(e); }
            return result;
        }
        try { disk.execute(() -> {
            try {
                var receipt = outbox.enqueue(command);
                if (receipt.isPresent()) finish(command, receipt.get(), null);
                else send(command, 0);
            } catch (Exception e) { finish(command, null, e); }
        }); } catch (RejectedExecutionException e) { finish(command, null, e); }
        return result;
    }
    private void send(TransferCommand command, int attempt) {
        if (closed.get()) return; // Durable PENDING row remains for restart.
        rpc.withDeadlineAfter(5, TimeUnit.SECONDS).advance(command, new StreamObserver<>() {
            private TransferResult response;
            @Override public void onNext(TransferResult r) { response = r; }
            @Override public void onCompleted() {
                if (response == null) { onError(Status.INTERNAL.asRuntimeException()); return; }
                try { disk.execute(() -> {
                    try { outbox.acknowledge(command.getOperation(), response); finish(command, response, null); }
                    catch (Exception e) { finish(command, null, e); }
                }); } catch (RejectedExecutionException e) { finish(command, null, e); }
            }
            @Override public void onError(Throwable error) {
                if (closed.get()) return;
                var code = Status.fromThrowable(error).getCode();
                if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED || code == Status.Code.RESOURCE_EXHAUSTED || code == Status.Code.ABORTED) {
                    long delay = Math.min(5000, 250L << Math.min(attempt, 5));
                    try { retries.schedule(() -> send(command, attempt + 1), delay, TimeUnit.MILLISECONDS); }
                    catch (RejectedExecutionException e) { finish(command, null, e); }
                } else finish(command, null, error); // Preserve row for operator reconciliation; never pretend committed.
            }
        });
    }
    private void finish(TransferCommand c, TransferResult result, Throwable error) {
        var future = active.remove(c.getOperation());
        if (future != null) {
            capacity.release();
            if (error == null) future.complete(result); else future.completeExceptionally(error);
        }
    }
    @Override public void close() throws Exception {
        closed.set(true); retries.shutdownNow(); channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        disk.shutdown();
        if (!disk.awaitTermination(15, TimeUnit.SECONDS)) throw new IllegalStateException("Outbox writer still active");
        outbox.close();
        active.values().forEach(f -> f.completeExceptionally(new CancellationException("Peer stopped; persisted commands recover on restart")));
        active.clear();
    }
}
