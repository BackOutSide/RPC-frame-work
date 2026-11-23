package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcTimeoutException;
import io.github.backoutside.rivet.factory.SingletonFactory;
import io.github.backoutside.rivet.registry.ServiceDiscovery;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.github.backoutside.rivet.remoting.dto.RpcMessage;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.github.backoutside.rivet.remoting.transport.netty.codec.RpcMessageCodec;
import io.github.backoutside.rivet.remoting.transport.netty.codec.RpcMessageFrameDecoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(12)
class NettyBoundedFailoverIntegrationTest {
    private static final InetSocketAddress PENDING_CONNECT_ADDRESS =
            InetSocketAddress.createUnresolved("simulated-pending-connect", 19001);

    private final NioEventLoopGroup io = new NioEventLoopGroup(1);
    private final UnprocessedRequests pending = SingletonFactory.getInstance(UnprocessedRequests.class);
    private NettyRpcClient client;

    @AfterEach
    void stopClientAndEndpoints() {
        if (client != null) {
            client.close();
        }
        io.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        RpcConfiguration.configure(null);
    }

    @Test
    void pendingFirstConnectLeavesTimeForRealSecondEndpoint() throws Exception {
        configure(1800, 350);
        Endpoint healthy = startEndpoint("second");
        // Only the first connection is simulated. The second uses the real wire format and TCP.
        try (PendingConnect first = new PendingConnect()) {
            client = newClient(connectorWith(first), PENDING_CONNECT_ADDRESS, healthy.address);

            RpcResponse<?> response = send("logical-connect", true);

            assertSuccess(response, "logical-connect", "second");
            assertEquals(1, first.calls.get());
            assertNotEquals("logical-connect", healthy.takeRequest().requestId());
            assertTrue(healthy.requests.isEmpty());
            assertFalse(first.promise.isDone(), "the caller's timeout must leave the shared connect pending");
            assertEquals(0, pending.size());
        }
    }

    @Test
    void disabledAttemptBudgetLeavesSecondEndpointUntouchedAfterPendingConnect() {
        configure(600, 0);
        Endpoint healthy = startEndpoint("second");
        try (PendingConnect first = new PendingConnect()) {
            client = newClient(connectorWith(first), PENDING_CONNECT_ADDRESS, healthy.address);

            RpcTimeoutException failure = assertThrows(RpcTimeoutException.class,
                    () -> send("logical-disabled", true));

            assertEquals("logical-disabled", failure.getRequestId());
            assertEquals(1, first.calls.get());
            assertTrue(healthy.requests.isEmpty());
            assertFalse(first.promise.isDone());
            assertEquals(0, pending.size());
        }
    }

    @Test
    void lateReplyFromFirstTcpEndpointCannotCompleteTheSecondAttempt() throws Exception {
        configure(3500, 500);
        Endpoint first = startEndpoint(null);
        Endpoint second = startEndpoint(null);
        client = newClient(null, first.address, second.address);
        // Install an observer before sending the RPC to verify delivery to the real client handler.
        Channel firstChannel = client.getChannel(first.address,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(3), "connect-before-observing");
        CompletableFuture<String> firstReplyHandled = observeReply(firstChannel);

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<RpcResponse<?>> call = caller.submit(() -> send("logical-delayed", true));
            WireRequest firstRequest = first.takeRequest();
            WireRequest secondRequest = second.takeRequest();
            assertNotEquals(firstRequest.requestId(), secondRequest.requestId());
            assertNotEquals("logical-delayed", firstRequest.requestId());
            assertNotEquals("logical-delayed", secondRequest.requestId());
            assertEquals(1, pending.size(), "the first attempt has been removed and only the second is pending");

            firstRequest.reply("late-first").get(2, TimeUnit.SECONDS);

            assertEquals(firstRequest.requestId(), firstReplyHandled.get(2, TimeUnit.SECONDS));
            assertFalse(call.isDone(), "a late first response must not complete the logical call");
            assertEquals(1, pending.size(), "the response from the first endpoint must not remove the second attempt");

            secondRequest.reply("second").get(2, TimeUnit.SECONDS);

            assertSuccess(call.get(3, TimeUnit.SECONDS), "logical-delayed", "second");
            assertEquals(0, pending.size());
            assertTrue(first.requests.isEmpty());
            assertTrue(second.requests.isEmpty());
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void nonIdempotentCallIsSentOnlyOnceWhenItsReplyIsMissing() throws Exception {
        configure(850, 300);
        Endpoint first = startEndpoint(null);
        Endpoint healthy = startEndpoint("second");
        client = newClient(null, first.address, healthy.address);

        RpcTimeoutException failure = assertThrows(RpcTimeoutException.class,
                () -> send("logical-write", false));

        assertEquals(RpcStatusCode.RESPONSE_TIMEOUT, failure.getKnownStatusCode());
        assertEquals("logical-write", failure.getRequestId());
        assertNotEquals("logical-write", first.takeRequest().requestId());
        assertTrue(first.requests.isEmpty());
        assertTrue(healthy.requests.isEmpty(), "the write outcome is unknown, so the call must not be replayed");
        assertEquals(0, pending.size());
    }

    @Test
    void twoResponseSilentTcpEndpointsShareOneLogicalDeadline() throws Exception {
        long totalBudgetMs = 1600;
        configure(totalBudgetMs, 650);
        Endpoint first = startEndpoint(null);
        Endpoint second = startEndpoint(null);
        client = newClient(null, first.address, second.address);
        long started = System.nanoTime();

        RpcTimeoutException failure = assertThrows(RpcTimeoutException.class,
                () -> send("logical-two-silent", true));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(RpcStatusCode.RESPONSE_TIMEOUT, failure.getKnownStatusCode());
        assertEquals("logical-two-silent", failure.getRequestId());
        assertEquals(1, first.requests.size());
        assertEquals(1, second.requests.size());
        assertTrue(elapsedMs >= totalBudgetMs - 100 && elapsedMs < totalBudgetMs + 500,
                "The final attempt should use the remaining logical budget. Elapsed: " + elapsedMs + " ms");
        assertEquals(0, pending.size());
    }

    private static void configure(long totalMs, int perAttemptMs) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("bounded-failover-test", Map.of(
                "rpc.client.timeout-ms", Long.toString(totalMs),
                "rpc.client.connect-timeout-ms", "5000",
                "rpc.client.max-attempts", "2",
                "rpc.client.per-attempt-timeout-ms", Integer.toString(perAttemptMs))));
        RpcConfiguration.configure(environment);
    }

    private static NettyRpcClient newClient(Function<InetSocketAddress, ChannelFuture> connector,
                                            InetSocketAddress first, InetSocketAddress second) {
        return new NettyRpcClient(connector, new ServiceDiscovery() {
            @Override
            public InetSocketAddress lookupService(RpcRequest request) {
                return first;
            }

            @Override
            public List<InetSocketAddress> lookupServices(RpcRequest request) {
                return List.of(first, second);
            }
        });
    }

    private RpcResponse<?> send(String logicalId, boolean idempotent) {
        return (RpcResponse<?>) client.sendRpcRequest(RpcRequest.builder()
                .requestId(logicalId).interfaceName("bounded.failover.TestService").methodName("invoke")
                .parameters(new Object[0]).paramTypes(new Class<?>[0]).group("").version("")
                .idempotent(idempotent).build());
    }

    private static void assertSuccess(RpcResponse<?> response, String logicalId, String reply) {
        assertEquals(RpcStatusCode.SUCCESS.getCode(), response.getCode());
        assertEquals(logicalId, response.getRequestId());
        assertEquals(reply, response.getData());
    }

    private Endpoint startEndpoint(String reply) {
        BlockingQueue<WireRequest> requests = new LinkedBlockingQueue<>();
        Channel listener = new ServerBootstrap().group(io).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socket) {
                        socket.pipeline().addLast(new RpcMessageFrameDecoder(), new RpcMessageCodec(),
                                new SimpleChannelInboundHandler<RpcMessage>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage message) {
                                        if (message.getData() instanceof RpcRequest request) {
                                            WireRequest captured = new WireRequest(ctx.channel(), request.getRequestId(),
                                                    message.getCodec(), message.getCompress());
                                            requests.add(captured);
                                            if (reply != null) {
                                                captured.reply(reply);
                                            }
                                        }
                                    }
                                });
                    }
                }).bind("127.0.0.1", 0).syncUninterruptibly().channel();
        return new Endpoint((InetSocketAddress) listener.localAddress(), requests);
    }

    private Function<InetSocketAddress, ChannelFuture> connectorWith(PendingConnect first) {
        Bootstrap tcp = new Bootstrap().group(io).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socket) {
                        socket.pipeline().addLast(new RpcMessageFrameDecoder(), new RpcMessageCodec(),
                                new NettyRpcClientHandler());
                    }
                });
        return address -> PENDING_CONNECT_ADDRESS.equals(address) ? first.connect() : tcp.connect(address);
    }

    private static CompletableFuture<String> observeReply(Channel clientChannel) {
        CompletableFuture<String> handled = new CompletableFuture<>();
        String handler = clientChannel.pipeline().context(NettyRpcClientHandler.class).name();
        clientChannel.pipeline().addBefore(handler, "observe-reply", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object message) {
                String responseId = message instanceof RpcMessage rpc && rpc.getData() instanceof RpcResponse<?> response
                        ? response.getRequestId() : null;
                ctx.fireChannelRead(message);
                if (responseId != null) {
                    handled.complete(responseId);
                }
            }
        });
        return handled;
    }

    private record Endpoint(InetSocketAddress address, BlockingQueue<WireRequest> requests) {
        WireRequest takeRequest() throws InterruptedException {
            WireRequest request = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(request, "No request reached " + address);
            return request;
        }
    }

    private record WireRequest(Channel channel, String requestId, byte codec, byte compression) {
        ChannelFuture reply(String payload) {
            return channel.writeAndFlush(RpcMessage.builder().messageType(RpcConstants.RESPONSE_TYPE)
                    .codec(codec).compress(compression).data(RpcResponse.success(payload, requestId)).build());
        }
    }

    private static final class PendingConnect implements AutoCloseable {
        private final EmbeddedChannel channel = new EmbeddedChannel();
        private final DefaultChannelPromise promise = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
        private final AtomicInteger calls = new AtomicInteger();

        private ChannelFuture connect() {
            calls.incrementAndGet();
            return promise;
        }

        @Override
        public void close() {
            promise.cancel(false);
            channel.finishAndReleaseAll();
        }
    }
}
