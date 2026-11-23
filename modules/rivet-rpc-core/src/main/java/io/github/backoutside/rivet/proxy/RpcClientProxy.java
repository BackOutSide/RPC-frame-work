package io.github.backoutside.rivet.proxy;

import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.annotation.RpcIdempotent;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.exception.RpcRejectedException;
import io.github.backoutside.rivet.exception.RpcRemoteException;
import io.github.backoutside.rivet.metrics.RpcMetrics;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.github.backoutside.rivet.remoting.transport.RpcRequestTransport;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * Dynamic proxy class.
 * When a dynamic proxy object calls a method, it actually calls the following invoke method.
 * It is precisely because of the dynamic proxy that the remote method called by the client is like calling the local method (the intermediate process is shielded)
 *
 * @author shuang.kou
 * @createTime 2020年05月10日 19:01:00
 */
@Slf4j
public class RpcClientProxy implements InvocationHandler {

    /**
     * Used to send requests to the server.And there are two implementations: socket and netty
     */
    private final RpcRequestTransport rpcRequestTransport;
    private final RpcServiceConfig rpcServiceConfig;

    public RpcClientProxy(RpcRequestTransport rpcRequestTransport, RpcServiceConfig rpcServiceConfig) {
        this.rpcRequestTransport = rpcRequestTransport;
        this.rpcServiceConfig = rpcServiceConfig;
    }


    public RpcClientProxy(RpcRequestTransport rpcRequestTransport) {
        this.rpcRequestTransport = rpcRequestTransport;
        this.rpcServiceConfig = new RpcServiceConfig();
    }

    /**
     * get the proxy object 
     */
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Class<T> clazz) {
        return (T) Proxy.newProxyInstance(
            clazz.getClassLoader(), 
            new Class<?>[]{clazz}, this);
    }

    /**
     * This method is actually called when you use a proxy object to call a method.
     * The proxy object is the object you get through the getProxy method.
     */
    @SuppressWarnings("unchecked")
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "RivetRPC proxy for " + proxy.getClass().getInterfaces()[0].getName();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
        long started = System.nanoTime();
        String outcome = "success";
        // 1. 创建一个PRC请求
        log.debug("invoked method: [{}]", method.getName());
        RpcRequest rpcRequest = RpcRequest.builder().methodName(method.getName())
                .parameters(args)
                .interfaceName(method.getDeclaringClass().getName())
                .paramTypes(method.getParameterTypes())
                .requestId(UUID.randomUUID().toString())
                .group(rpcServiceConfig.getGroup())
                .version(rpcServiceConfig.getVersion())
                .idempotent(method.isAnnotationPresent(RpcIdempotent.class))
                .build();
        try {
            RpcResponse<Object> rpcResponse = (RpcResponse<Object>) rpcRequestTransport.sendRpcRequest(rpcRequest);
            outcome = RpcMetrics.outcome(rpcResponse);
            this.check(rpcResponse, rpcRequest);
            return rpcResponse.getData();
        } catch (RuntimeException ex) {
            if ("success".equals(outcome)) {
                outcome = RpcMetrics.outcome(ex);
            }
            throw ex;
        } finally {
            RpcMetrics.client(rpcRequest.getRpcServiceName(), method.getName(),
                    rpcRequestTransport.getClass().getSimpleName(), outcome, System.nanoTime() - started);
        }
    }

    private void check(RpcResponse<Object> rpcResponse, RpcRequest rpcRequest) {
        if (rpcResponse == null) {
            throw invalidResponse(rpcRequest, "RPC returned no response");
        }

        if (!rpcRequest.getRequestId().equals(rpcResponse.getRequestId())) {
            throw invalidResponse(rpcRequest, "RPC response request ID does not match");
        }

        if (rpcResponse.getCode() == null) {
            throw invalidResponse(rpcRequest, "RPC response has no status code");
        }
        if (!rpcResponse.getCode().equals(RpcStatusCode.SUCCESS.getCode())) {
            if (Integer.valueOf(RpcStatusCode.OVERLOADED.getCode()).equals(rpcResponse.getCode())) {
                throw new RpcRejectedException(rpcResponse.getCode(), rpcRequest.getRequestId(),
                        "RPC provider overloaded");
            }
            throw new RpcRemoteException(rpcResponse.getCode(), rpcResponse.getRequestId(),
                    rpcResponse.getMessage());
        }
    }

    private RpcException invalidResponse(RpcRequest request, String message) {
        return new RpcException(RpcStatusCode.INVALID_RESPONSE, request.getRequestId(), message, null);
    }
}
