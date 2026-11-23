package io.github.backoutside.rivet.proxy;

import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.exception.RpcRejectedException;
import io.github.backoutside.rivet.exception.RpcRemoteException;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.github.backoutside.rivet.remoting.transport.RpcRequestTransport;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcClientProxyStatusTest {
    private static final AtomicInteger NEXT_SERVICE_ID = new AtomicInteger();
    private SimpleMeterRegistry metrics;
    private String serviceName;

    interface Probe {
        String call();
    }

    @BeforeEach
    void installMetrics() {
        metrics = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(metrics);
    }

    @AfterEach
    void removeMetrics() {
        Metrics.globalRegistry.remove(metrics);
        metrics.close();
    }

    @Test
    void remoteFailureKeepsItsCodeAndRequestId() {
        Probe probe = proxy(request -> RpcResponse.fail(RpcStatusCode.SERVICE_NOT_FOUND,
                request.getRequestId()));

        RpcRemoteException error = assertThrows(RpcRemoteException.class, probe::call);
        assertEquals(RpcStatusCode.SERVICE_NOT_FOUND.getCode(), error.getCode());
        assertEquals(RpcStatusCode.SERVICE_NOT_FOUND, error.getKnownStatusCode());
        assertEquals(36, error.getRequestId().length());
        assertOutcome("remote_error");
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
        assertOutcome("remote_error");
    }

    @Test
    void remoteFrameworkFailureRetainsUnavailableOutcome() {
        Probe probe = proxy(request -> RpcResponse.fail(RpcStatusCode.INTERNAL_ERROR,
                request.getRequestId()));

        RpcRemoteException error = assertThrows(RpcRemoteException.class, probe::call);
        assertEquals(RpcStatusCode.INTERNAL_ERROR.getCode(), error.getCode());
        assertOutcome("unavailable");
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
        assertOutcome("protocol_error");
    }

    @Test
    void mismatchedRequestIdIsAClientSideInvalidResponse() {
        Probe probe = proxy(request -> RpcResponse.success("value", "another-request"));

        RpcException error = assertThrows(RpcException.class, probe::call);
        assertEquals(RpcStatusCode.INVALID_RESPONSE.getCode(), error.getCode());
        assertOutcome("protocol_error");
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
        assertOutcome("rejected");
    }

    private void assertOutcome(String outcome) {
        assertEquals(1, metrics.get("rivet.rpc.client.duration")
                .tags("service", serviceName, "method", "call", "outcome", outcome)
                .timer().count());
    }

    private Probe proxy(RpcRequestTransport transport) {
        String group = "metrics-" + NEXT_SERVICE_ID.incrementAndGet();
        serviceName = Probe.class.getName() + group;
        RpcServiceConfig service = RpcServiceConfig.builder().group(group).version("").build();
        return new RpcClientProxy(transport, service).getProxy(Probe.class);
    }
}
