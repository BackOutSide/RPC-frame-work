package github.javaguide.remoting.transport.netty.client;


import github.javaguide.enums.CompressTypeEnum;
import github.javaguide.enums.SerializationTypeEnum;
import github.javaguide.enums.ServiceDiscoveryEnum;
import github.javaguide.extension.ExtensionLoader;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.loadbalance.ActiveRequestsRecorder;
import github.javaguide.registry.ServiceDiscovery;
import github.javaguide.remoting.constants.RpcConstants;
import github.javaguide.remoting.dto.RpcMessage;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;
import github.javaguide.remoting.transport.RpcRequestTransport;
import github.javaguide.remoting.transport.netty.codec.RpcMessageCodec;
import github.javaguide.remoting.transport.netty.codec.RpcMessageFrameDecoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * initialize and close Bootstrap object
 *
 */
@Slf4j
public final class NettyRpcClient implements RpcRequestTransport {
    // 服务发现 用于发现服务
    private final ServiceDiscovery serviceDiscovery;
    // 未处理请求 用于存储未处理请求
    private final UnprocessedRequests unprocessedRequests;
    // 通道提供者 用于提供通道
    private final ChannelProvider channelProvider;
    // 活跃请求记录器 用于LeastActive负载均衡
    private final ActiveRequestsRecorder activeRequestsRecorder;
    // Bootstrap 用于创建客户端
    private final Bootstrap bootstrap;
    // 事件循环组 用于处理事件
    private final EventLoopGroup eventLoopGroup;

    /**
     * 初始化资源
     */
    public NettyRpcClient() {
        // 初始化事件循环组
        eventLoopGroup = new NioEventLoopGroup();
        // RPC消息编码器
        RpcMessageCodec rpcMessageCodec = new RpcMessageCodec();
        // Bootstrap
        bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                //  The timeout period of the connection.
                //  If this time is exceeded or the connection cannot be established, the connection fails.
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // 当客户端超过5秒没有发送数据时，发送心跳请求
                        p.addLast(new IdleStateHandler(0, 5, 0, TimeUnit.SECONDS));
                        // RPCMessageFrame  解码器
                        p.addLast(new RpcMessageFrameDecoder());
                        // RPCMessage       编解码器
                        p.addLast(rpcMessageCodec);
                        //客户端事件处理器
                        p.addLast(new NettyRpcClientHandler());
                    }
                });
        this.serviceDiscovery = ExtensionLoader.getExtensionLoader(ServiceDiscovery.class).getExtension(ServiceDiscoveryEnum.ZK.getName());
        this.unprocessedRequests = SingletonFactory.getInstance(UnprocessedRequests.class);
        this.channelProvider = SingletonFactory.getInstance(ChannelProvider.class);
        this.activeRequestsRecorder = SingletonFactory.getInstance(ActiveRequestsRecorder.class);
    }

    /**
     * 连接服务器并获取通道，以便可以发送RPC消息到服务器
     *
     * @param inetSocketAddress server address
     * @return the channel
     */
    @SneakyThrows
    public Channel doConnect(InetSocketAddress inetSocketAddress) {
        CompletableFuture<Channel> completableFuture = new CompletableFuture<>();
        bootstrap.connect(inetSocketAddress).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                log.info("The client has connected [{}] successful!", inetSocketAddress.toString());
                completableFuture.complete(future.channel());
            } else {
                throw new IllegalStateException();
            }
        });
        return completableFuture.get();
    }

    /**
     * 发送RPC请求
     * @param rpcRequest
     * @return
     */
    @Override
    public Object sendRpcRequest(RpcRequest rpcRequest) {
        CompletableFuture<RpcResponse<Object>> resultFuture = new CompletableFuture<>();
        // 1. 获取服务的地址
        InetSocketAddress inetSocketAddress = serviceDiscovery.lookupService(rpcRequest);
        String rpcServiceName = rpcRequest.getRpcServiceName();
        String serviceAddress = inetSocketAddress.getHostString() + ":" + inetSocketAddress.getPort();
        // 2. 获取channel
        Channel channel = getChannel(inetSocketAddress);
        if (channel.isActive()) {
            // 3.发送请求
            activeRequestsRecorder.increment(rpcServiceName, serviceAddress);
            resultFuture.whenComplete((response, throwable) -> activeRequestsRecorder.decrement(rpcServiceName, serviceAddress));
            unprocessedRequests.put(rpcRequest.getRequestId(), resultFuture);
            RpcMessage rpcMessage = RpcMessage.builder().data(rpcRequest)
                    .codec(SerializationTypeEnum.HESSIAN.getCode())
                    .compress(CompressTypeEnum.GZIP.getCode())
                    .messageType(RpcConstants.REQUEST_TYPE).build();
            channel.writeAndFlush(rpcMessage).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    log.info("client send message: [{}]", rpcMessage);
                } else {
                    future.channel().close();
                    unprocessedRequests.remove(rpcRequest.getRequestId());
                    resultFuture.completeExceptionally(future.cause());
                    log.error("Send failed:", future.cause());
                }
            });
        } else {
            throw new IllegalStateException();
        }
        // 4. 得到响应的结果
        try {
            return resultFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("rpc请求失败," + e.getMessage());
        }
    }

    /**
     * 获取通道 如果通道不存在，则创建通道
     * @param inetSocketAddress
     * @return
     */
    public Channel getChannel(InetSocketAddress inetSocketAddress) {
        Channel channel = channelProvider.get(inetSocketAddress);
        if (channel == null) {
            channel = doConnect(inetSocketAddress);
            channelProvider.set(inetSocketAddress, channel);
        }
        return channel;
    }

    public void close() {
        eventLoopGroup.shutdownGracefully();
    }
}
