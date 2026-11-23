package io.github.backoutside.rivet.proxy;

import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.exception.RpcRejectedException;
import io.github.backoutside.rivet.exception.RpcRemoteException;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.github.backoutside.rivet.remoting.transport.RpcRequestTransport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcClientProxyStatusTest {
    interface Probe {
        String call();
    }

    @Test
    void remoteFailureKeepsItsCodeAndRequestId() {
        Probe probe = proxy(request -> RpcResponse.fail(RpcStatusCode.SERVICE_NOT_FOUND,
                request.getRequestId()));

        RpcRemoteException error = assertThrows(RpcRemoteException.class, probe::call);
        assertEquals(RpcStatusCode.SERVICE_NOT_FOUND.getCode(), error.getCode());
        assertEquals(RpcStatusCode.SERVICE_NOT_FOUND, error.getKnownStatusCode());
        assertEquals(36, error.getRequestId().length());
    }

    @Test
    void unknownRemoteCodeIsPreserved() {
        Probe probe = proxy(request -> {
            RpcResponse<Object> response = RpcResponse.success(null, request.getRequestId());
            response.setCode(8801);
            response.setMessage("Future provider status");
            return response;
        });

        RpcRemoteException error = assertThrows(RpcRemoteException.class, probe::call);
        assertEquals(8801, error.getCode());
        assertNull(error.getKnownStatusCode());
    }

    @Test
    void missingResponseCodeIsAClientSideInvalidResponse() {
        Probe probe = proxy(request -> {
            RpcResponse<Object> response = RpcResponse.success(null, request.getRequestId());
            response.setCode(null);
            return response;
        });

        RpcException error = assertThrows(RpcException.class, probe::call);
        assertEquals(RpcStatusCode.INVALID_RESPONSE.getCode(), error.getCode());
    }

    @Test
    void mismatchedRequestIdIsAClientSideInvalidResponse() {
        Probe probe = proxy(request -> RpcResponse.success("value", "another-request"));

        RpcException error = assertThrows(RpcException.class, probe::call);
        assertEquals(RpcStatusCode.INVALID_RESPONSE.getCode(), error.getCode());
    }

    @Test
    void existingOverloadResponseKeepsItsRejectionException() {
        Probe probe = proxy(request -> {
            RpcResponse<Object> response = RpcResponse.success(null, request.getRequestId());
            response.setCode(503);
            return response;
        });

        RpcRejectedException error = assertThrows(RpcRejectedException.class, probe::call);
        assertEquals(503, error.getCode());
    }

    private Probe proxy(RpcRequestTransport transport) {
        return new RpcClientProxy(transport).getProxy(Probe.class);
    }
}
