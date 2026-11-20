package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.enums.CompressTypeEnum;
import io.github.backoutside.rivet.enums.SerializationTypeEnum;
import io.github.backoutside.rivet.enums.ServiceDiscoveryEnum;
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
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Synchronous RPC API backed by an asynchronous Netty transport. */
@Slf4j
public final class NettyRpcClient implements RpcRequestTransport {
    private final ServiceDiscovery serviceDiscovery;
    private final UnprocessedRequests unprocessedRequests;
    private final ChannelProvider channelProvider;
    private final ActiveRequestsRecorder activeRequestsRecorder;
    private final Bootstrap bootstrap;
    private final EventLoopGroup eventLoopGroup;

    public NettyRpcClient() {
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
        serviceDiscovery = ExtensionLoader.getExtensionLoader(ServiceDiscovery.class)
                .getExtension(ServiceDiscoveryEnum.ZK.getName());
        unprocessedRequests = SingletonFactory.getInstance(UnprocessedRequests.class);
        channelProvider = SingletonFactory.getInstance(ChannelProvider.class);
        activeRequestsRecorder = SingletonFactory.getInstance(ActiveRequestsRecorder.class);
    }

    public Channel doConnect(InetSocketAddress address) {
        ChannelFuture connect = bootstrap.connect(address);
        try {
            long deadlineMs = RpcConfiguration.current().getConnectTimeoutMs() + 1000L;
            if (!connect.await(deadlineMs)) {
                connect.cancel(true);
                throw new RpcTimeoutException("RPC connection timed out: " + address, null);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            connect.cancel(true);
            throw new RpcUnavailableException("Interrupted while connecting to " + address, ex);
        }
        if (!connect.isSuccess()) {
            throw new RpcUnavailableException("Could not connect to RPC provider " + address, connect.cause());
        }
        return connect.channel();
    }

    @Override
    public Object sendRpcRequest(RpcRequest request) {
        InetSocketAddress address;
        try {
            address = serviceDiscovery.lookupService(request);
        } catch (RpcException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new RpcUnavailableException("RPC service discovery failed", ex);
        }

        Channel channel = getChannel(address);
        if (!channel.isActive()) {
            throw new RpcUnavailableException("RPC provider channel is inactive: " + address);
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
            channel.writeAndFlush(message).addListener((ChannelFutureListener) write -> {
                if (!write.isSuccess()) {
                    unprocessedRequests.remove(request.getRequestId());
                    responseFuture.completeExceptionally(
                            new RpcUnavailableException("Could not send RPC request", write.cause()));
                    write.channel().close();
                }
            });
            return responseFuture.get(RpcConfiguration.current().getRequestTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new RpcTimeoutException("RPC request timed out: " + serviceName, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RpcUnavailableException("Interrupted while waiting for RPC response", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RpcException) {
                throw (RpcException) cause;
            }
            throw new RpcUnavailableException("RPC request failed", cause);
        } finally {
            unprocessedRequests.remove(request.getRequestId());
            if (!responseFuture.isDone()) {
                responseFuture.completeExceptionally(new RpcTimeoutException("RPC request ended without a response", null));
            }
        }
    }

    public Channel getChannel(InetSocketAddress address) {
        Channel channel = channelProvider.get(address);
        if (channel != null) {
            return channel;
        }
        synchronized (channelProvider) {
            channel = channelProvider.get(address);
            if (channel == null) {
                channel = doConnect(address);
                channelProvider.set(address, channel);
            }
        }
        return channel;
    }

    public void close() {
        channelProvider.closeAll();
        eventLoopGroup.shutdownGracefully();
        log.debug("RPC client stopped");
    }
}
