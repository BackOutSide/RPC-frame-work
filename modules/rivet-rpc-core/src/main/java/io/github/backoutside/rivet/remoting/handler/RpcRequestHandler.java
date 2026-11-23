package io.github.backoutside.rivet.remoting.handler;

import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.factory.SingletonFactory;
import io.github.backoutside.rivet.provider.ServiceProvider;
import io.github.backoutside.rivet.provider.impl.ZkServiceProviderImpl;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * RpcRequest processor
 *
 * @author shuang.kou
 * @createTime 2020年05月13日 09:05:00
 */
@Slf4j
public class RpcRequestHandler {
    private final ServiceProvider serviceProvider;

    public RpcRequestHandler() {
        serviceProvider = SingletonFactory.getInstance(ZkServiceProviderImpl.class);
    }

    /**
     * Processing rpcRequest: call the corresponding method, and then return the method
     */
    public Object handle(RpcRequest rpcRequest) {
        validate(rpcRequest);
        Object service = serviceProvider.getService(rpcRequest.getRpcServiceName());
        return invokeTargetMethod(rpcRequest, service);
    }

    private void validate(RpcRequest request) {
        if (request == null) {
            throw new RpcException(RpcStatusCode.INVALID_REQUEST, null,
                    "RPC request is missing", null);
        }
        String requestId = request.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            throw new RpcException(RpcStatusCode.INVALID_REQUEST, null,
                    "RPC request ID is missing", null);
        }
        if (request.getInterfaceName() == null || request.getInterfaceName().isBlank()
                || request.getMethodName() == null || request.getMethodName().isBlank()
                || request.getGroup() == null || request.getVersion() == null
                || request.getParamTypes() == null) {
            throw new RpcException(RpcStatusCode.INVALID_REQUEST, requestId,
                    "RPC request is missing required fields", null);
        }
        Class<?>[] paramTypes = request.getParamTypes();
        Object[] parameters = request.getParameters();
        if ((parameters == null ? 0 : parameters.length) != paramTypes.length) {
            throw new RpcException(RpcStatusCode.INVALID_REQUEST, requestId,
                    "RPC argument count does not match the method signature", null);
        }
        for (Class<?> paramType : paramTypes) {
            if (paramType == null) {
                throw new RpcException(RpcStatusCode.INVALID_REQUEST, requestId,
                        "RPC parameter type is missing", null);
            }
        }
    }

    /**
     * get method execution results
     *
     * @param rpcRequest client request
     * @param service    service object
     * @return the result of the target method execution
     */
    private Object invokeTargetMethod(RpcRequest rpcRequest, Object service) {
        Object result;
        try {
            Method method = service.getClass().getMethod(rpcRequest.getMethodName(), rpcRequest.getParamTypes());
            result = method.invoke(service, rpcRequest.getParameters());
            log.debug("service:[{}] invoked method:[{}]", rpcRequest.getInterfaceName(), rpcRequest.getMethodName());
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            throw new RpcException(RpcStatusCode.PROVIDER_EXCEPTION, rpcRequest.getRequestId(),
                    "RPC provider method failed", target);
        } catch (NoSuchMethodException e) {
            throw new RpcException(RpcStatusCode.METHOD_NOT_FOUND, rpcRequest.getRequestId(),
                    "RPC method not found", e);
        } catch (IllegalArgumentException e) {
            throw new RpcException(RpcStatusCode.INVALID_REQUEST, rpcRequest.getRequestId(),
                    "RPC arguments do not match the method signature", e);
        } catch (IllegalAccessException e) {
            throw new RpcException(RpcStatusCode.INTERNAL_ERROR, rpcRequest.getRequestId(),
                    "RPC method is inaccessible", e);
        }
        return result;
    }
}
