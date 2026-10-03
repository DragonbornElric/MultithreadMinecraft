package dev.mtmc.coordination;

import dev.mtmc.coordination.protocol.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.concurrent.*;

/** A bounded writer queue separates transport event loops from transactional disk operations. */
public final class AuthorityService extends AuthorityGrpc.AuthorityImplBase implements AutoCloseable {
    private final TransferLedger ledger;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), Thread.ofPlatform().name("mtmc-ledger-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    public AuthorityService(TransferLedger ledger) { this.ledger = ledger; }
    @FunctionalInterface private interface Work<T> { T run(String peer) throws Exception; }
    private <T> void submit(Work<T> work, StreamObserver<T> response) {
        String peer = PeerIdentity.NODE.get();
        Context context = Context.current();
        if (peer == null) { response.onError(Status.UNAUTHENTICATED.asRuntimeException()); return; }
        try {
            writer.execute(() -> {
                if (context.isCancelled()) { response.onError(Status.CANCELLED.asRuntimeException()); return; }
                try {
                    T result = work.run(peer);
                    response.onNext(result); response.onCompleted();
                } catch (IllegalArgumentException e) {
                    response.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
                } catch (SQLException e) {
                    response.onError(Status.ABORTED.withDescription("Ledger transaction failed; reconcile before retry").asRuntimeException());
                } catch (Exception e) {
                    response.onError(Status.INTERNAL.withDescription("Authority operation failed").asRuntimeException());
                }
            });
        } catch (RejectedExecutionException e) {
            response.onError(Status.RESOURCE_EXHAUSTED.withDescription("Ledger queue full").asRuntimeException());
        }
    }
    @Override public void getSession(SessionQuery r, StreamObserver<NodeSession> o) { submit(peer -> ledger.getSession(peer, r), o); }
    @Override public void openSession(SessionStart r, StreamObserver<NodeSession> o) { submit(peer -> ledger.openSession(peer, r), o); }
    @Override public void checkAdmission(AdmissionCheck r, StreamObserver<AdmissionDecision> o) { submit(peer -> ledger.checkAdmission(peer, r), o); }
    @Override public void admit(Admission r, StreamObserver<Owner> o) { submit(peer -> ledger.admit(peer, r), o); }
    @Override public void lookup(PlayerKey r, StreamObserver<Owner> o) { submit(peer -> ledger.lookup(peer, r), o); }
    @Override public void advance(TransferCommand r, StreamObserver<TransferResult> o) { submit(peer -> ledger.advance(peer, r), o); }
    @Override public void inspect(TransferKey r, StreamObserver<TransferSnapshot> o) { submit(peer -> ledger.inspect(peer, r), o); }
    @Override public void close() throws Exception {
        writer.shutdown();
        if (!writer.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("Ledger writer still running; do not close database");
        ledger.close();
    }
}
