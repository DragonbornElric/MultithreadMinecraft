package dev.mtmc.lab;

import com.google.gson.*;
import dev.mtmc.coordination.PeerIdentity;
import dev.mtmc.coordination.TransferLedger;
import dev.mtmc.coordination.protocol.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Explicit disposable lab only: no clients, world ownership lease, restore or shared-state mutation. */
public final class LabPeer implements DedicatedServerModInitializer {
    private static volatile LabPeer instance;
    private final String node = System.getProperty("mtmc.lab.node", "");
    private final String other = System.getProperty("mtmc.lab.other", "");
    private final String dimension = System.getProperty("mtmc.lab.dimension", "");
    private final String otherDimension = System.getProperty("mtmc.lab.otherDimension", "");
    private final String boot = UUID.randomUUID().toString();
    private final AtomicLong ticks = new AtomicLong(), received = new AtomicLong(), sent = new AtomicLong(), failures = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> blocked = new ConcurrentHashMap<>();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicReference<DimensionReport> report = new AtomicReference<>();
    private Server endpoint;
    private ManagedChannel channel;
    private ScheduledExecutorService timer;

    @Override public void onInitializeServer() {
        if (!Boolean.getBoolean("mtmc.lab.peer.enabled") || Boolean.getBoolean("mtmc.cluster.enabled"))
            throw new IllegalStateException("Lab mod requires explicit disposable peer mode; not cluster gameplay");
        var dimensions = Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
        if (!Set.of("a", "b").contains(node) || !Set.of("a", "b").contains(other) || node.equals(other)
            || !dimensions.contains(dimension) || !dimensions.contains(otherDimension) || dimension.equals(otherDimension))
            throw new IllegalArgumentException("Two distinct lab nodes/dimensions required");
        instance = this;
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> dispatcher.register(Commands.literal("mtmc-lab-peer")
            .requires(Commands.hasPermission(Commands.LEVEL_ADMINS)).executes(c -> {
                c.getSource().sendSuccess(() -> Component.literal(status().toString()), false); return 1;
            })));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { start(); } catch (Exception e) { throw new IllegalStateException("Lab peer startup failed", e); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
    }
    public static boolean allowTick(String dimension) {
        var peer = instance;
        if (peer == null) return false;
        if (peer.dimension.equals(dimension)) return true;
        peer.blocked.computeIfAbsent(dimension, key -> new AtomicLong()).incrementAndGet(); return false;
    }
    public static void completedTick(String dimension) {
        var peer = instance;
        if (peer != null && peer.dimension.equals(dimension)) peer.ticks.incrementAndGet();
    }
    private Path file(String name) { return Path.of(System.getProperty("mtmc.lab.pki")).resolve(name); }
    private void start() throws Exception {
        endpoint = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", Integer.getInteger("mtmc.lab.port")))
            .sslContext(GrpcSslContexts.forServer(file(node+".pem").toFile(), file(node+".key").toFile())
                .trustManager(file("ca.pem").toFile()).clientAuth(ClientAuth.REQUIRE).build())
            .maxInboundMessageSize(4096).maxConcurrentCallsPerConnection(4)
            .intercept(new PeerIdentity("lab", Set.of(other)))
            .addService(new NodeProbeGrpc.NodeProbeImplBase() {
                @Override public void report(ProbeRequest r, StreamObserver<DimensionReport> out) {
                    if (r.getVersion()!=TransferLedger.VERSION || r.getNonce()<=0) {
                        out.onError(Status.FAILED_PRECONDITION.asRuntimeException()); return;
                    }
                    received.incrementAndGet();
                    out.onNext(DimensionReport.newBuilder().setVersion(TransferLedger.VERSION).setNonce(r.getNonce())
                        .setNode(node).setDimension(dimension).setBoot(boot).setCompletedTicks(ticks.get())
                        .setAuthenticatedRequester(PeerIdentity.NODE.get()).build()); out.onCompleted();
                }
            }).build().start();
        channel = NettyChannelBuilder.forAddress("localhost", Integer.getInteger("mtmc.lab.otherPort"))
            .maxInboundMessageSize(4096).sslContext(GrpcSslContexts.forClient().trustManager(file("ca.pem").toFile())
                .keyManager(file(node+".pem").toFile(), file(node+".key").toFile()).build()).build();
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("mtmc-lab-peer").factory());
        timer.scheduleWithFixedDelay(this::probe, 0, 500, TimeUnit.MILLISECONDS);
        System.out.println("MTMC_LAB_PEER ready node="+node+" dimension="+dimension+" port="+endpoint.getPort());
    }
    private void probe() {
        if (!inFlight.compareAndSet(false,true)) return;
        long nonce = sent.incrementAndGet();
        NodeProbeGrpc.newStub(channel).withDeadlineAfter(2,TimeUnit.SECONDS).report(ProbeRequest.newBuilder()
            .setVersion(TransferLedger.VERSION).setNonce(nonce).build(), new StreamObserver<>() {
                private DimensionReport value;
                @Override public void onNext(DimensionReport r) { value=r; }
                @Override public void onError(Throwable error) { failures.incrementAndGet(); inFlight.set(false); }
                @Override public void onCompleted() {
                    if (value==null || value.getVersion()!=TransferLedger.VERSION || value.getNonce()!=nonce || !value.getNode().equals(other)
                        || !value.getDimension().equals(otherDimension) || !value.getAuthenticatedRequester().equals(node)) failures.incrementAndGet();
                    else {
                        var previous=report.getAndSet(value);
                        if (previous==null || !previous.getBoot().equals(value.getBoot()))
                            System.out.println("MTMC_LAB_PEER received node="+value.getNode()+" dimension="+value.getDimension()+" boot="+value.getBoot()+" ticks="+value.getCompletedTicks());
                    }
                    inFlight.set(false);
                }
            });
    }
    private JsonObject status() {
        var out=new JsonObject(); out.addProperty("node",node); out.addProperty("dimension",dimension); out.addProperty("boot",boot);
        out.addProperty("completed_ticks",ticks.get()); out.addProperty("received",received.get()); out.addProperty("sent",sent.get()); out.addProperty("failures",failures.get());
        var counts=new JsonObject(); blocked.forEach((key,value)->counts.addProperty(key,value.get())); out.add("blocked_dimension_ticks",counts);
        var current=report.get();
        if (current!=null) {
            var peer=new JsonObject(); peer.addProperty("node",current.getNode());peer.addProperty("dimension",current.getDimension());peer.addProperty("boot",current.getBoot());
            peer.addProperty("completed_ticks",current.getCompletedTicks());peer.addProperty("nonce",current.getNonce());peer.addProperty("authenticated_requester",current.getAuthenticatedRequester());out.add("peer",peer);
        }
        return out;
    }
    private void stop() {
        if (timer!=null) timer.shutdownNow();
        if (channel!=null) channel.shutdownNow();
        if (endpoint!=null) endpoint.shutdownNow();
    }
}
