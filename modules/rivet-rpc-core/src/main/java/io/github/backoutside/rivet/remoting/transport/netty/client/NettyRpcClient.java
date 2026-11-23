package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.enums.CompressTypeEnum;
import io.github.backoutside.rivet.enums.SerializationTypeEnum;
import io.github.backoutside.rivet.enums.ServiceDiscoveryEnum;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.exception.RpcTimeoutException;
import io.github.backoutside.rivet.exception.RpcUnavailableException;
import io.github.backoutside.rivet.extension.ExtensionLoader;
import io.github.backoutside.rivet.factory.SingletonFactory;
import io.github.backoutside.rivet.loadbalance.ActiveRequestsRecorder;
import io.github.backoutside.rivet.registry.ServiceDiscovery;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.github.backoutside.rivet.remoting.dto.RpcMessage;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.github.backoutside.rivet.remoting.transport.RpcRequestTransport;
import io.github.backoutside.rivet.remoting.transport.netty.codec.RpcMessageCodec;
import io.github.backoutside.rivet.remoting.transport.netty.codec.RpcMessageFrameDecoder;
import io.github.backoutside.rivet.tracing.RpcTracing;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Synchronous RPC API backed by an asynchronous Netty transport. */
@Slf4j
public final class NettyRpcClient implements RpcRequestTransport {
    private final ServiceDiscovery serviceDiscovery;
    private final UnprocessedRequests unprocessedRequests;
    private final ChannelProvider channelProvider;
    private final ActiveRequestsRecorder activeRequestsRecorder;
    private final Bootstrap bootstrap;
    private final EventLoopGroup eventLoopGroup;
    private final RpcCallExecutor callExecutor;
    private final ConcurrentMap<InetSocketAddress, CompletableFuture<Channel>> connecting = new ConcurrentHashMap<>();
    private final ConcurrentMap<InetSocketAddress, ChannelFuture> nettyConnecting = new ConcurrentHashMap<>();
    private final Function<InetSocketAddress, ChannelFuture> connector;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    public NettyRpcClient() {
        this(null);
    }

    NettyRpcClient(Function<InetSocketAddress, ChannelFuture> connector) {
        this(connector, ExtensionLoader.getExtensionLoader(ServiceDiscovery.class)
                .getExtension(ServiceDiscoveryEnum.ZK.getName()));
    }

    NettyRpcClient(Function<InetSocketAddress, ChannelFuture> connector, ServiceDiscovery discovery) {
        RpcConfiguration config = RpcConfiguration.current();
        eventLoopGroup = new NioEventLoopGroup();
        RpcMessageCodec codec = new RpcMessageCodec();
        bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMs())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline.addLast(new IdleStateHandler(0, 5, 0, TimeUnit.SECONDS));
                        pipeline.addLast(new RpcMessageFrameDecoder());
                        pipeline.addLast(codec);
                        pipeline.addLast(new NettyRpcClientHandler());
                    }
                });
        this.connector = connector == null ? bootstrap::connect : connector;
        serviceDiscovery = discovery;
        unprocessedRequests = SingletonFactory.getInstance(UnprocessedRequests.class);
        channelProvider = SingletonFactory.getInstance(ChannelProvider.class);
        activeRequestsRecorder = SingletonFactory.getInstance(ActiveRequestsRecorder.class);
        callExecutor = new RpcCallExecutor(config, serviceDiscovery, this::sendAttempt);
        Gauge.builder("rivet.rpc.client.pending", unprocessedRequests, UnprocessedRequests::size)
                .register(Metrics.globalRegistry);
    }

    @Override
    public Object sendRpcRequest(RpcRequest request) {
        Span span = RpcTracing.startClient(request);
        Integer status = null;
        Throwable failure = null;
        try (Scope scope = RpcTracing.scope(span)) {
            RpcResponse<Object> response = callExecutor.execute(request);
            status = response.getCode();
            return response;
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            RpcTracing.finish(span, status, failure);
        }
    }

    private RpcResponse<Object> sendAttempt(RpcRequest request, InetSocketAddress address, long remainingNanos) {
        if (remainingNanos <= 0) {
            throw new RpcTimeoutException(RpcStatusCode.RESPONSE_TIMEOUT, request.getRequestId(),
                    "RPC call timed out before sending " + request.getRpcServiceName(), null);
        }
        long deadline = System.nanoTime() + remainingNanos;
        Channel channel = getChannel(address, deadline, request.getRequestId());
        if (deadline - System.nanoTime() <= 0) {
            throw new RpcTimeoutException(RpcStatusCode.RESPONSE_TIMEOUT, request.getRequestId(),
                    "RPC call timed out before sending to " + address, null);
        }
        if (!channel.isActive()) {
            throw new RpcUnavailableException(RpcStatusCode.CONNECT_FAILED, request.getRequestId(),
                    "RPC provider channel is inactive: " + address, null);
        }
        String serviceName = request.getRpcServiceName();
        String serviceAddress = address.getHostString() + ":" + address.getPort();
        CompletableFuture<RpcResponse<Object>> responseFuture = new CompletableFuture<>();
        activeRequestsRecorder.increment(serviceName, serviceAddress);
        responseFuture.whenComplete((response, error) ->
                activeRequestsRecorder.decrement(serviceName, serviceAddress));
        try {
            unprocessedRequests.put(request.getRequestId(), channel, responseFuture);
            RpcMessage message = RpcMessage.builder()
                    .data(request)
                    .codec(SerializationTypeEnum.HESSIAN.getCode())
                    .compress(CompressTypeEnum.GZIP.getCode())
                    .messageType(RpcConstants.REQUEST_TYPE).build();
            if (deadline - System.nanoTime() <= 0) {
                throw new RpcTimeoutException(RpcStatusCode.RESPONSE_TIMEOUT, request.getRequestId(),
                        "RPC call timed out before sending " + serviceName, null);
            }
            channel.writeAndFlush(message).addListener((ChannelFutureListener) write -> {
                if (!write.isSuccess()) {
                    unprocessedRequests.remove(request.getRequestId());
                    responseFuture.completeExceptionally(writeFailure(request.getRequestId(), write.cause()));
                    write.channel().close();
                }
            });
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new RpcTimeoutException(RpcStatusCode.RESPONSE_TIMEOUT, request.getRequestId(),
                        "RPC response timed out for " + serviceName, null);
            }
            return responseFuture.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            throw new RpcTimeoutException(RpcStatusCode.RESPONSE_TIMEOUT, request.getRequestId(),
                    "RPC response timed out: " + serviceName, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RpcException(RpcStatusCode.CALL_INTERRUPTED, request.getRequestId(),
                    "Interrupted while waiting for RPC response", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RpcException) {
                throw (RpcException) cause;
            }
            throw new RpcUnavailableException(RpcStatusCode.CONNECTION_LOST, request.getRequestId(),
                    "RPC request failed before a response", cause);
        } finally {
            unprocessedRequests.remove(request.getRequestId());
            if (!responseFuture.isDone()) {
                responseFuture.completeExceptionally(new RpcTimeoutException(
                        RpcStatusCode.RESPONSE_TIMEOUT, request.getRequestId(),
                        "RPC request ended without a response", null));
            }
        }
    }

    private RpcException writeFailure(String requestId, Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof RpcException) {
                RpcStatusCode code = ((RpcException) current).getKnownStatusCode();
                if (code == RpcStatusCode.CODEC_ERROR || code == RpcStatusCode.PROTOCOL_ERROR) {
                    return new RpcException(code, requestId, code.getMessage(), cause);
                }
            }
        }
        return new RpcUnavailableException(RpcStatusCode.SEND_FAILED, requestId,
                "Could not send RPC request", cause);
    }

    Channel getChannel(InetSocketAddress address, long deadline, String requestId) {
        if (closed.get()) {
            throw new RpcUnavailableException(RpcStatusCode.CONNECTION_LOST, requestId,
                    "RPC client is closed", null);
        }
        if (deadline - System.nanoTime() <= 0) {
            throw new RpcTimeoutException(RpcStatusCode.CONNECT_TIMEOUT, requestId,
                    "RPC connection timed out before connecting to " + address, null);
        }
        Channel channel = channelProvider.get(address);
        if (channel != null) {
            return channel;
        }
        CompletableFuture<Channel> mine = new CompletableFuture<>();
        CompletableFuture<Channel> existing = connecting.putIfAbsent(address, mine);
        if (existing == null) {
            mine.whenComplete((connected, error) -> connecting.remove(address, mine));
            channel = channelProvider.get(address);
            if (channel != null) {
                mine.complete(channel);
            } else if (closed.get()) {
                mine.completeExceptionally(new RpcUnavailableException(
                        RpcStatusCode.CONNECTION_LOST, null, "RPC client is closed", null));
            } else {
                // The Netty connect timeout belongs to the shared connection. A caller's
                // shorter deadline only stops that caller from waiting on its result.
                try {
                    ChannelFuture pendingConnect = connector.apply(address);
                    nettyConnecting.put(address, pendingConnect);
                    if (closed.get()) {
                        pendingConnect.cancel(true);
                    }
                    pendingConnect.addListener((ChannelFutureListener) connect -> {
                        nettyConnecting.remove(address, connect);
                        if (!connect.isSuccess()) {
                            Throwable cause = connect.cause();
                            mine.completeExceptionally(cause == null
                                    ? new IllegalStateException("RPC connection ended without a cause") : cause);
                        } else {
                            synchronized (lifecycleLock) {
                                if (closed.get()) {
                                    connect.channel().close();
                                    mine.completeExceptionally(new RpcUnavailableException(
                                            RpcStatusCode.CONNECTION_LOST, null, "RPC client is closed", null));
                                } else {
                                    channelProvider.set(address, connect.channel());
                                    mine.complete(connect.channel());
                                }
                            }
                        }
                    });
                } catch (RuntimeException ex) {
                    mine.completeExceptionally(ex);
                }
            }
        }
        CompletableFuture<Channel> shared = existing == null ? mine : existing;
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new RpcTimeoutException(RpcStatusCode.CONNECT_TIMEOUT, requestId,
                    "RPC connection timed out while waiting for " + address, null);
        }
        try {
            return shared.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            throw new RpcTimeoutException(RpcStatusCode.CONNECT_TIMEOUT, requestId,
                    "RPC connection wait timed out: " + address, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RpcException(RpcStatusCode.CALL_INTERRUPTED, requestId,
                    "Interrupted while waiting for connection to " + address, ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof ConnectTimeoutException) {
                throw new RpcTimeoutException(RpcStatusCode.CONNECT_TIMEOUT, requestId,
                        "RPC connection timed out: " + address, ex.getCause());
            }
            if (ex.getCause() instanceof RpcException rpcException) {
                RpcStatusCode code = rpcException.getKnownStatusCode();
                if (code != null) {
                    if (rpcException instanceof RpcTimeoutException) {
                        throw new RpcTimeoutException(code, requestId, rpcException.getMessage(), rpcException);
                    }
                    if (rpcException instanceof RpcUnavailableException) {
                        throw new RpcUnavailableException(code, requestId, rpcException.getMessage(), rpcException);
                    }
                    throw new RpcException(code, requestId, rpcException.getMessage(), rpcException);
                }
                throw rpcException;
            }
            throw new RpcUnavailableException(RpcStatusCode.CONNECT_FAILED, requestId,
                    "Could not connect to RPC provider " + address, ex.getCause());
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        connecting.values().forEach(future -> future.completeExceptionally(new RpcUnavailableException(
                RpcStatusCode.CONNECTION_LOST, null, "RPC client is closed", null)));
        nettyConnecting.values().forEach(connect -> connect.cancel(true));
        nettyConnecting.clear();
        synchronized (lifecycleLock) {
            channelProvider.closeAll();
        }
        eventLoopGroup.shutdownGracefully();
        log.debug("RPC client stopped");
    }
}
