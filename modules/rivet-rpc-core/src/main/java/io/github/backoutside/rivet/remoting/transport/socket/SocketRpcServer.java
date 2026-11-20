package io.github.backoutside.rivet.remoting.transport.socket;

import io.github.backoutside.rivet.config.CustomShutdownHook;
import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.factory.SingletonFactory;
import io.github.backoutside.rivet.provider.ServiceProvider;
import io.github.backoutside.rivet.provider.impl.ZkServiceProviderImpl;
import io.github.backoutside.rivet.utils.concurrent.threadpool.ThreadPoolFactoryUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;

import static io.github.backoutside.rivet.remoting.transport.netty.server.NettyRpcServer.PORT;

/**
 * 基于Socket的RPC服务器
 */
@Slf4j
public class SocketRpcServer {

    // 线程池 用于处理RPC请求
    private final ExecutorService threadPool;
    // 服务提供者 用于提供服务
    private final ServiceProvider serviceProvider;

    /**
     * 初始化线程池和服务提供者
     */
    public SocketRpcServer() {
        threadPool = ThreadPoolFactoryUtil.createCustomThreadPoolIfAbsent("socket-server-rpc-pool");
        serviceProvider = SingletonFactory.getInstance(ZkServiceProviderImpl.class);
    }

    /**
     * 注册服务
     * @param rpcServiceConfig
     */
    public void registerService(RpcServiceConfig rpcServiceConfig) {
        serviceProvider.publishService(rpcServiceConfig);
    }

    /**
     * 启动服务器
     */
    public void start() {
        try (ServerSocket server = new ServerSocket()) {
            String host = InetAddress.getLocalHost().getHostAddress();
            server.bind(new InetSocketAddress(host, PORT));
            CustomShutdownHook.getCustomShutdownHook().clearAll();
            Socket socket;
            while ((socket = server.accept()) != null) {
                log.info("client connected [{}]", socket.getInetAddress());
                threadPool.execute(new SocketRpcRequestHandlerRunnable(socket));
            }
            threadPool.shutdown();
        } catch (IOException e) {
            log.error("occur IOException:", e);
        }
    }

}
