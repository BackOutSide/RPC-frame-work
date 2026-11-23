package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcUnavailableException;
import io.github.backoutside.rivet.factory.SingletonFactory;
import io.github.backoutside.rivet.provider.impl.ZkServiceProviderImpl;
import io.github.backoutside.rivet.registry.ServiceDiscovery;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.github.backoutside.rivet.remoting.dto.RpcMessage;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.github.backoutside.rivet.remoting.transport.netty.codec.RpcMessageCodec;
import io.github.backoutside.rivet.remoting.transport.netty.codec.RpcMessageFrameDecoder;
import io.github.backoutside.rivet.remoting.transport.netty.server.NettyTracingTestHandlers;
import io.github.backoutside.rivet.remoting.transport.netty.server.RpcAdmissionHandler;
import io.github.backoutside.rivet.tracing.RpcTracing;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NettyTracingIntegrationTest {
    private final List<LocalServer> servers = new ArrayList<>();
    private final List<NettyRpcClient> clients = new ArrayList<>();
    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk telemetry;
    private TraceServiceImpl service;
    private String group;

    @BeforeEach
    void setUp() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("tracing-test", Map.of(
                "rpc.client.timeout-ms", "10000",
                "rpc.client.max-attempts", "2")));
        RpcConfiguration.configure(environment);
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        telemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(telemetry);
        group = "trace-" + UUID.randomUUID();
        service = new TraceServiceImpl();
        ZkServiceProviderImpl.getInstance().addService(RpcServiceConfig.builder()
                .service(service).interfaceClass(TraceService.class)
                .group(group).version("v1").build());
    }

    @AfterEach
    void tearDown() {
        for (NettyRpcClient client : clients) {
            client.close();
        }
        for (LocalServer server : servers) {
            server.close();
        }
        telemetry.close();
        GlobalOpenTelemetry.resetForTest();
        RpcConfiguration.configure(null);
    }

    @Test
    void twoCallsInOneParentKeepTheirTraceAcrossDiscoveryAndServerThreads() {
        LocalServer server = startServer(2);
        NettyRpcClient client = clientFor(List.of(server.address));
        Span parent = telemetry.getTracer("tracing-test").spanBuilder("parent").startSpan();
        String traceId = parent.getSpanContext().getTraceId();
        String parentSpanId = parent.getSpanContext().getSpanId();
        RpcResponse<?> first;
        RpcResponse<?> second;
        try (Scope ignored = parent.makeCurrent()) {
            assertEquals(traceId, RpcTracing.currentRequestId());
            first = (RpcResponse<?>) client.sendRpcRequest(request("first", "logical-first", false));
            second = (RpcResponse<?>) client.sendRpcRequest(request("second", "logical-second", false));
            assertEquals(traceId, RpcTracing.currentRequestId());
        } finally {
            parent.end();
        }
        assertNull(RpcTracing.currentRequestId());
        assertEquals(RpcStatusCode.SUCCESS.getCode(), first.getCode());
        assertEquals(RpcStatusCode.SUCCESS.getCode(), second.getCode());
        assertEquals("logical-first", first.getRequestId());
        assertEquals("logical-second", second.getRequestId());
        assertEquals(traceId, first.getData());
        assertEquals(traceId, second.getData());
        assertEquals(2, server.attempts.size());
        assertNotEquals(server.attempts.get(0).requestId, server.attempts.get(1).requestId);
        assertNotEquals("logical-first", server.attempts.get(0).requestId);
        assertNotEquals("logical-second", server.attempts.get(1).requestId);

        List<SpanData> spans = awaitSpans(5);
        assertEquals(2, count(spans, SpanKind.CLIENT));
        assertEquals(2, count(spans, SpanKind.SERVER));
        assertEquals(1, count(spans, SpanKind.INTERNAL));
        assertTrue(spans.stream().allMatch(span -> traceId.equals(span.getTraceId())));
        List<SpanData> clientSpans = spans.stream().filter(span -> span.getKind() == SpanKind.CLIENT).toList();
        List<SpanData> serverSpans = spans.stream().filter(span -> span.getKind() == SpanKind.SERVER).toList();
        assertTrue(clientSpans.stream().allMatch(span -> parentSpanId.equals(span.getParentSpanId())));
        for (SpanData serverSpan : serverSpans) {
            SpanData clientSpan = clientSpans.stream().filter(span ->
                    span.getName().equals(serverSpan.getName())).findFirst().orElseThrow();
            assertEquals(clientSpan.getSpanId(), serverSpan.getParentSpanId());
            assertEquals(StatusCode.UNSET, serverSpan.getStatus().getStatusCode());
        }
        assertEquals(0, SingletonFactory.getInstance(UnprocessedRequests.class).size());
    }

    @Test
    void overloadRetryUsesOneLogicalSpanAndDistinctPhysicalRequestIds() {
        LocalServer overloaded = startServer(0);
        LocalServer healthy = startServer(2);
        NettyRpcClient client = clientFor(List.of(overloaded.address, healthy.address));
        RpcRequest request = request("first", "logical-retry", true);
        RpcResponse<?> response = (RpcResponse<?>) client.sendRpcRequest(request);

        assertEquals(RpcStatusCode.SUCCESS.getCode(), response.getCode());
        assertEquals("logical-retry", response.getRequestId());
        assertNotNull(response.getData());
        assertEquals(1, overloaded.attempts.size());
        assertEquals(1, healthy.attempts.size());
        Attempt firstAttempt = overloaded.attempts.get(0);
        Attempt secondAttempt = healthy.attempts.get(0);
        assertNotEquals(firstAttempt.requestId, secondAttempt.requestId);
        assertNotEquals(request.getRequestId(), firstAttempt.requestId);
        assertNotEquals(request.getRequestId(), secondAttempt.requestId);
        assertNotNull(firstAttempt.traceparent);
        assertEquals(firstAttempt.traceparent, secondAttempt.traceparent);

        List<SpanData> spans = awaitSpans(3);
        assertEquals(1, count(spans, SpanKind.CLIENT));
        assertEquals(2, count(spans, SpanKind.SERVER));
        SpanData clientSpan = only(spans, SpanKind.CLIENT);
        assertEquals(response.getData(), clientSpan.getTraceId());
        assertEquals(StatusCode.UNSET, clientSpan.getStatus().getStatusCode());
        assertTrue(spans.stream().allMatch(span -> span.getTraceId().equals(clientSpan.getTraceId())));
        assertTrue(spans.stream().filter(span -> span.getKind() == SpanKind.SERVER)
                .allMatch(span -> span.getParentSpanId().equals(clientSpan.getSpanId())));
        assertEquals(1, spans.stream().filter(span -> span.getKind() == SpanKind.SERVER
                && span.getStatus().getStatusCode() == StatusCode.ERROR).count());
        assertEquals(1, spans.stream().filter(span -> span.getKind() == SpanKind.SERVER
                && span.getStatus().getStatusCode() == StatusCode.UNSET).count());
        assertEquals(0, SingletonFactory.getInstance(UnprocessedRequests.class).size());
    }

    @Test
    void failedProviderCallEndsBothSpansAndDoesNotLeakContextToNextCall() {
        LocalServer server = startServer(1);
        NettyRpcClient client = clientFor(List.of(server.address));
        RpcResponse<?> failed = (RpcResponse<?>) client.sendRpcRequest(request("fail", "logical-fail", false));
        RpcResponse<?> success = (RpcResponse<?>) client.sendRpcRequest(request("first", "logical-after-fail", false));

        assertEquals(RpcStatusCode.PROVIDER_EXCEPTION.getCode(), failed.getCode());
        assertEquals("logical-fail", failed.getRequestId());
        assertEquals(RpcStatusCode.SUCCESS.getCode(), success.getCode());
        assertEquals("logical-after-fail", success.getRequestId());
        assertNull(RpcTracing.currentRequestId());
        List<SpanData> spans = awaitSpans(4);
        SpanData failedClient = named(spans, SpanKind.CLIENT, "fail");
        SpanData failedServer = named(spans, SpanKind.SERVER, "fail");
        SpanData nextClient = named(spans, SpanKind.CLIENT, "first");
        SpanData nextServer = named(spans, SpanKind.SERVER, "first");
        assertEquals(StatusCode.ERROR, failedClient.getStatus().getStatusCode());
        assertEquals(StatusCode.ERROR, failedServer.getStatus().getStatusCode());
        assertEquals(failedClient.getSpanId(), failedServer.getParentSpanId());
        assertEquals(nextClient.getSpanId(), nextServer.getParentSpanId());
        assertNotEquals(failedClient.getTraceId(), nextClient.getTraceId());
        assertEquals(nextClient.getTraceId(), success.getData());
        assertEquals(0, SingletonFactory.getInstance(UnprocessedRequests.class).size());
    }

    @Test
    void concurrentCallsOnOneChannelKeepTheirOwnTraceAndResponse() throws Exception {
        LocalServer server = startServer(2);
        NettyRpcClient client = clientFor(List.of(server.address));
        RpcResponse<?> warmup = (RpcResponse<?>) client.sendRpcRequest(request("first", "warmup", false));
        assertEquals(RpcStatusCode.SUCCESS.getCode(), warmup.getCode());
        service.holdEntered = new CountDownLatch(1);
        service.releaseHold = new CountDownLatch(1);

        ExecutorService callers = Executors.newFixedThreadPool(2);
        CallResult left;
        CallResult right;
        try {
            Future<CallResult> leftFuture = callers.submit(() -> concurrentCall(client, "left"));
            assertTrue(service.holdEntered.await(3, TimeUnit.SECONDS));
            Future<CallResult> rightFuture = callers.submit(() -> concurrentCall(client, "right"));
            awaitAttempts(server, 3);
            service.releaseHold.countDown();
            left = leftFuture.get(10, TimeUnit.SECONDS);
            right = rightFuture.get(10, TimeUnit.SECONDS);
        } finally {
            service.releaseHold.countDown();
            callers.shutdownNow();
        }

        assertEquals(RpcStatusCode.SUCCESS.getCode(), left.response.getCode());
        assertEquals(RpcStatusCode.SUCCESS.getCode(), right.response.getCode());
        assertEquals(left.traceId, left.response.getData());
        assertEquals(right.traceId, right.response.getData());
        assertNotEquals(left.traceId, right.traceId);
        assertEquals(3, server.attempts.size());
        String channelId = server.attempts.get(0).channelId;
        assertEquals(channelId, server.attempts.get(1).channelId);
        assertEquals(channelId, server.attempts.get(2).channelId);

        List<SpanData> spans = awaitSpans(8);
        for (CallResult call : List.of(left, right)) {
            SpanData clientSpan = spans.stream().filter(span -> span.getKind() == SpanKind.CLIENT
                    && span.getTraceId().equals(call.traceId)).findFirst().orElseThrow();
            SpanData serverSpan = spans.stream().filter(span -> span.getKind() == SpanKind.SERVER
                    && span.getTraceId().equals(call.traceId)).findFirst().orElseThrow();
            assertEquals(call.parentSpanId, clientSpan.getParentSpanId());
            assertEquals(clientSpan.getSpanId(), serverSpan.getParentSpanId());
        }
        assertEquals(0, SingletonFactory.getInstance(UnprocessedRequests.class).size());
    }

    @Test
    void heartbeatDoesNotCreateAnRpcSpan() {
        RpcAdmissionHandler admission = new RpcAdmissionHandler(1);
        io.netty.channel.embedded.EmbeddedChannel channel = new io.netty.channel.embedded.EmbeddedChannel(
                admission, NettyTracingTestHandlers.withAdmission(admission));
        try {
            channel.writeInbound(RpcMessage.builder()
                    .messageType(RpcConstants.HEARTBEAT_REQUEST_TYPE).build());
            RpcMessage reply = channel.readOutbound();
            assertEquals(RpcConstants.HEARTBEAT_RESPONSE_TYPE, reply.getMessageType());
            assertTrue(exporter.getFinishedSpanItems().isEmpty());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void missingProviderFinishesTheClientSpanWithoutLeavingContextBehind() {
        NettyRpcClient client = clientFor(List.of());
        RpcUnavailableException failure = assertThrows(RpcUnavailableException.class,
                () -> client.sendRpcRequest(request("first", "logical-missing", false)));
        assertEquals(RpcStatusCode.NO_PROVIDER.getCode(), failure.getCode());
        assertEquals("logical-missing", failure.getRequestId());
        assertNull(RpcTracing.currentRequestId());
        List<SpanData> spans = awaitSpans(1);
        assertEquals(1, count(spans, SpanKind.CLIENT));
        assertEquals(0, count(spans, SpanKind.SERVER));
        assertEquals(StatusCode.ERROR, only(spans, SpanKind.CLIENT).getStatus().getStatusCode());
        assertEquals(0, SingletonFactory.getInstance(UnprocessedRequests.class).size());
    }

    private LocalServer startServer(int maxInFlight) {
        LocalServer server = new LocalServer(maxInFlight);
        servers.add(server);
        return server;
    }

    private NettyRpcClient clientFor(List<InetSocketAddress> addresses) {
        ServiceDiscovery discovery = new ServiceDiscovery() {
            @Override
            public InetSocketAddress lookupService(RpcRequest request) {
                return addresses.get(0);
            }

            @Override
            public List<InetSocketAddress> lookupServices(RpcRequest request) {
                return addresses;
            }
        };
        NettyRpcClient client = new NettyRpcClient(null, discovery);
        clients.add(client);
        return client;
    }

    private RpcRequest request(String method, String requestId, boolean idempotent) {
        return RpcRequest.builder().requestId(requestId).interfaceName(TraceService.class.getName())
                .methodName(method).paramTypes(new Class<?>[0]).parameters(new Object[0])
                .group(group).version("v1").idempotent(idempotent).build();
    }

    private CallResult concurrentCall(NettyRpcClient client, String requestId) {
        Span parent = telemetry.getTracer("tracing-test").spanBuilder("parent-" + requestId).startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            RpcResponse<?> response = (RpcResponse<?>) client.sendRpcRequest(
                    request("hold", requestId, false));
            return new CallResult(parent.getSpanContext().getTraceId(),
                    parent.getSpanContext().getSpanId(), response);
        } finally {
            parent.end();
        }
    }

    private List<SpanData> awaitSpans(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        List<SpanData> spans;
        do {
            spans = exporter.getFinishedSpanItems();
            if (spans.size() >= expected) {
                return spans;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for spans", ex);
            }
        } while (System.nanoTime() < deadline);
        assertEquals(expected, spans.size(), "timed out waiting for finished spans");
        return spans;
    }

    private static void awaitAttempts(LocalServer server, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (server.attempts.size() < expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertEquals(expected, server.attempts.size(), "second request did not reach the same channel");
    }

    private static long count(List<SpanData> spans, SpanKind kind) {
        return spans.stream().filter(span -> span.getKind() == kind).count();
    }

    private static SpanData only(List<SpanData> spans, SpanKind kind) {
        List<SpanData> matches = spans.stream().filter(span -> span.getKind() == kind).toList();
        assertEquals(1, matches.size());
        return matches.get(0);
    }

    private static SpanData named(List<SpanData> spans, SpanKind kind, String method) {
        return spans.stream().filter(span -> span.getKind() == kind
                && span.getName().endsWith("/" + method)).findFirst().orElseThrow();
    }

    public interface TraceService {
        String first();

        String second();

        String fail();

        String hold() throws Exception;
    }

    public static class TraceServiceImpl implements TraceService {
        private volatile CountDownLatch holdEntered;
        private volatile CountDownLatch releaseHold;

        @Override
        public String first() {
            return RpcTracing.currentRequestId();
        }

        @Override
        public String second() {
            return RpcTracing.currentRequestId();
        }

        @Override
        public String fail() {
            throw new IllegalStateException("provider failure for tracing test");
        }

        @Override
        public String hold() throws Exception {
            holdEntered.countDown();
            if (!releaseHold.await(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the held RPC");
            }
            return RpcTracing.currentRequestId();
        }
    }

    private record CallResult(String traceId, String parentSpanId, RpcResponse<?> response) {
    }

    private static final class Attempt {
        private final String requestId;
        private final String traceparent;

        private final String channelId;

        private Attempt(RpcRequest request, String channelId) {
            requestId = request.getRequestId();
            traceparent = request.getTraceparent();
            this.channelId = channelId;
        }
    }

    private static final class LocalServer implements AutoCloseable {
        private final EventLoopGroup boss = new NioEventLoopGroup(1);
        private final EventLoopGroup worker = new NioEventLoopGroup(1);
        private final DefaultEventExecutorGroup business = new DefaultEventExecutorGroup(2);
        private final CopyOnWriteArrayList<Attempt> attempts = new CopyOnWriteArrayList<>();
        private final Channel listener;
        private final InetSocketAddress address;

        private LocalServer(int maxInFlight) {
            RpcAdmissionHandler admission = new RpcAdmissionHandler(maxInFlight);
            listener = new ServerBootstrap().group(boss, worker).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socket) {
                            socket.pipeline().addLast(new RpcMessageFrameDecoder());
                            socket.pipeline().addLast(new RpcMessageCodec());
                            socket.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object message) {
                                    if (message instanceof RpcMessage rpc
                                            && rpc.getData() instanceof RpcRequest request) {
                                        attempts.add(new Attempt(request, ctx.channel().id().asLongText()));
                                    }
                                    ctx.fireChannelRead(message);
                                }
                            });
                            socket.pipeline().addLast(admission);
                            socket.pipeline().addLast(business,
                                    NettyTracingTestHandlers.withAdmission(admission));
                        }
                    }).bind("127.0.0.1", 0).syncUninterruptibly().channel();
            address = (InetSocketAddress) listener.localAddress();
        }

        @Override
        public void close() {
            listener.close().syncUninterruptibly();
            business.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
            worker.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
            boss.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }
}
