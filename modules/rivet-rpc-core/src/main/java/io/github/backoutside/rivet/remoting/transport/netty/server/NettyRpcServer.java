package io.github.backoutside.rivet.remoting.transport.netty.server;

import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.provider.impl.ZkServiceProviderImpl;
import io.github.backoutside.rivet.remoting.transport.netty.codec.RpcMessageCodec;
import io.github.backoutside.rivet.remoting.transport.netty.codec.RpcMessageFrameDecoder;
import io.github.backoutside.rivet.utils.RuntimeUtil;
import io.github.backoutside.rivet.utils.concurrent.threadpool.ThreadPoolFactoryUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** The RPC listener is registered with ZooKeeper only after TCP bind succeeds. */
@Slf4j
@Component
public class NettyRpcServer implements ApplicationListener<ApplicationEvent>, DisposableBean {
    /** Legacy demo default. Runtime configuration may override this port. */
    public static final int PORT = 9998;

    // The gauge must observe a stable object across stop/start. Micrometer ignores
    // registration of a second gauge with the same name and tags.
    private static final Set<RpcAdmissionHandler> ACTIVE_ADMISSIONS = ConcurrentHashMap.newKeySet();

    static {
        Gauge.builder("rivet.rpc.server.inflight", ACTIVE_ADMISSIONS,
                        admissions -> admissions.stream().mapToInt(RpcAdmissionHandler::inFlight).sum())
                .register(Metrics.globalRegistry);
    }

    private final ZkServiceProviderImpl serviceProvider = ZkServiceProviderImpl.getInstance();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private DefaultEventExecutorGroup serviceHandlerGroup;
    private Channel serverChannel;
    private InetSocketAddress advertisedAddress;
    private RpcAdmissionHandler admission;

    public NettyRpcServer() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "rpc-server-shutdown"));
    }

    public void registerService(RpcServiceConfig config) {
        serviceProvider.publishService(config);
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ApplicationReadyEvent) {
            // ApplicationRunner initialization has completed at this point.
            start();
        } else if (event instanceof ContextRefreshedEvent) {
            // Non-Boot AnnotationConfigApplicationContext demos have no Ready event.
            Object context = ((ContextRefreshedEvent) event).getApplicationContext();
            if (!context.getClass().getName().startsWith("org.springframework.boot.")) {
                start();
            }
        }
    }

    public synchronized void start() {
        if (serverChannel != null && serverChannel.isActive()) {
            return;
        }
        if (!serviceProvider.hasServices()) {
            return; // Consumer-only applications do not open an RPC server port.
        }

        RpcConfiguration config = RpcConfiguration.current();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        serviceHandlerGroup = new DefaultEventExecutorGroup(
                RuntimeUtil.cpus() * 2,
                ThreadPoolFactoryUtil.createThreadFactory("rpc-service-handler", false),
                config.getServerMaxInflight(), RejectedExecutionHandlers.reject());
        RpcMessageCodec codec = new RpcMessageCodec();
        RpcAdmissionHandler admission = new RpcAdmissionHandler(config.getServerMaxInflight());
        this.admission = admission;
        ACTIVE_ADMISSIONS.add(admission);
        NettyRpcServerHandler handler = new NettyRpcServerHandler(admission);
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            ChannelPipeline pipeline = channel.pipeline();
                            pipeline.addLast(new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS));
                            pipeline.addLast(new RpcMessageFrameDecoder());
                            pipeline.addLast(codec);
                            pipeline.addLast(admission);
                            pipeline.addLast(serviceHandlerGroup, handler);
                        }
                    });
            ChannelFuture bound = bootstrap.bind(config.getBindHost(), config.getServerPort()).syncUninterruptibly();
            serverChannel = bound.channel();
            int boundPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
            advertisedAddress = new InetSocketAddress(config.getAdvertiseHost(), boundPort);
            serviceProvider.registerAll(advertisedAddress);
            log.info("RPC server listening on {}:{}, advertised as {}", config.getBindHost(), boundPort, advertisedAddress);
        } catch (RuntimeException ex) {
            stop();
            throw ex;
        }
    }

    public synchronized void stop() {
        if (admission != null) {
            ACTIVE_ADMISSIONS.remove(admission);
            admission = null;
        }
        if (advertisedAddress != null) {
            try {
                serviceProvider.unregisterAll();
            } catch (RuntimeException ex) {
                log.warn("Could not unregister RPC services during shutdown", ex);
            }
            advertisedAddress = null;
        }
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        if (serviceHandlerGroup != null) {
            serviceHandlerGroup.shutdownGracefully();
            serviceHandlerGroup = null;
        }
    }

    @Override
    public void destroy() {
        stop();
    }
}
