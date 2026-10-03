package dev.mtmc.coordination;

import io.grpc.*;
import java.security.cert.X509Certificate;
import java.util.Set;
import javax.net.ssl.SSLSession;

/** CA trust authenticates certificates; a URI SAN binds them to one allowlisted cluster node. */
public final class PeerIdentity implements ServerInterceptor {
    public static final Context.Key<String> NODE = Context.key("mtmc-peer");
    private final String cluster;
    private final Set<String> nodes;
    public PeerIdentity(String cluster, Set<String> nodes) {
        if (!cluster.matches("[a-zA-Z0-9_-]{1,64}") || nodes.stream().anyMatch(n -> !n.matches("[a-zA-Z0-9_-]{1,64}")))
            throw new IllegalArgumentException("Invalid cluster/node IDs");
        this.cluster = cluster; this.nodes = Set.copyOf(nodes);
    }
    @Override public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        try {
            SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
            if (session == null) throw new SecurityException("TLS required");
            X509Certificate cert = (X509Certificate) session.getPeerCertificates()[0];
            cert.checkValidity();
            String prefix = "spiffe://mtmc/" + cluster + "/";
            String node = null;
            var sans = cert.getSubjectAlternativeNames();
            if (sans != null) for (var san : sans) {
                if (Integer.valueOf(6).equals(san.get(0)) && san.get(1) instanceof String uri && uri.startsWith(prefix)) {
                    if (node != null) throw new SecurityException("Ambiguous peer identity");
                    node = uri.substring(prefix.length());
                }
            }
            if (!nodes.contains(node)) throw new SecurityException("Peer not allowlisted");
            if (Context.current().getDeadline() == null) throw new SecurityException("RPC deadline required");
            return Contexts.interceptCall(Context.current().withValue(NODE, node), call, headers, next);
        } catch (Exception e) {
            call.close(Status.UNAUTHENTICATED.withDescription("Peer identity/deadline rejected"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
    }
}
