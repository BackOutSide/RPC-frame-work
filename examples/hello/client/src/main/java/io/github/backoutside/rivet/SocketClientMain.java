package io.github.backoutside.rivet;

import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.proxy.RpcClientProxy;
import io.github.backoutside.rivet.remoting.transport.RpcRequestTransport;
import io.github.backoutside.rivet.remoting.transport.socket.SocketRpcClient;

/**
 * @author shuang.kou
 * @createTime 2020年05月10日 07:25:00
 */
public class SocketClientMain {
    public static void main(String[] args) {
        RpcRequestTransport rpcRequestTransport = new SocketRpcClient();
        RpcServiceConfig rpcServiceConfig = new RpcServiceConfig();
        RpcClientProxy rpcClientProxy = new RpcClientProxy(rpcRequestTransport, rpcServiceConfig);
        HelloService helloService = rpcClientProxy.getProxy(HelloService.class);
        String hello = helloService.hello(new Hello("111", "222"));
        System.out.println(hello);
    }
}
