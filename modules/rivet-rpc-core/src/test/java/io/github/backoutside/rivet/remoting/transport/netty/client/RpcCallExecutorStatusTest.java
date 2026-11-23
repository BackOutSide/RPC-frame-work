package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.exception.RpcRejectedException;
import io.github.backoutside.rivet.exception.RpcTimeoutException;
import io.github.backoutside.rivet.exception.RpcUnavailableException;
import io.github.backoutside.rivet.registry.ServiceDiscovery;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcCallExecutorStatusTest {
    private static final InetSocketAddress FIRST = InetSocketAddress.createUnresolved("provider-a", 19001);
    private static final InetSocketAddress SECOND = InetSocketAddress.createUnresolved("provider-b", 19002);

    @AfterEach
    void resetConfiguration() {
        RpcConfiguration.configure(new StandardEnvironment());
    }

    @Test
    void discoveryFailureAndEmptyProvidersHaveDistinctCodes() {
        RpcCallExecutor failed = new RpcCallExecutor(config(1000, 1),
                discovery(request -> { throw new IllegalStateException("ZooKeeper unavailable"); }),
                (request, address, remaining) -> { throw new AssertionError("should not send"); });
        RpcUnavailableException discoveryFailure = assertThrows(RpcUnavailableException.class,
                () -> failed.execute(request("discovery-id", false)));
        assertEquals(RpcStatusCode.DISCOVERY_FAILED.getCode(), discoveryFailure.getCode());
        assertEquals("discovery-id", discoveryFailure.getRequestId());
        assertInstanceOf(IllegalStateException.class, discoveryFailure.getCause());

        RpcCallExecutor empty = new RpcCallExecutor(config(1000, 1), discovery(request -> List.of()),
                (request, address, remaining) -> { throw new AssertionError("should not send"); });
        RpcUnavailableException noProvider = assertThrows(RpcUnavailableException.class,
                () -> empty.execute(request("empty-id", false)));
        assertEquals(RpcStatusCode.NO_PROVIDER.getCode(), noProvider.getCode());
        assertEquals("empty-id", noProvider.getRequestId());
    }

    @Test
    void emptyAndMismatchedRepliesAreInvalidResponses() {
        RpcCallExecutor empty = new RpcCallExecutor(config(1000, 1), discovery(request -> List.of(FIRST)),
                (request, address, remaining) -> null);
        RpcException missing = assertThrows(RpcException.class,
                () -> empty.execute(request("missing-reply", false)));
        assertEquals(RpcStatusCode.INVALID_RESPONSE.getCode(), missing.getCode());
        assertEquals("missing-reply", missing.getRequestId());

        RpcCallExecutor mismatched = new RpcCallExecutor(config(1000, 1), discovery(request -> List.of(FIRST)),
                (request, address, remaining) -> RpcResponse.success("value", "another-id"));
        RpcException mismatch = assertThrows(RpcException.class,
                () -> mismatched.execute(request("mismatch-reply", false)));
        assertEquals(RpcStatusCode.INVALID_RESPONSE.getCode(), mismatch.getCode());
        assertEquals("mismatch-reply", mismatch.getRequestId());
    }

    @Test
    void physicalTimeoutKeepsStatusAndCauseAtLogicalBoundary() {
        RpcCallExecutor executor = new RpcCallExecutor(config(1000, 1), discovery(request -> List.of(FIRST)),
                (physical, address, remaining) -> {
                    throw new RpcTimeoutException(RpcStatusCode.RESPONSE_TIMEOUT, physical.getRequestId(),
                            "provider did not reply", null);
                });

        RpcTimeoutException failure = assertThrows(RpcTimeoutException.class,
                () -> executor.execute(request("logical-timeout", false)));
        assertEquals(RpcStatusCode.RESPONSE_TIMEOUT.getCode(), failure.getCode());
        assertEquals("logical-timeout", failure.getRequestId());
        RpcTimeoutException physical = assertInstanceOf(RpcTimeoutException.class, failure.getCause());
        assertNotEquals("logical-timeout", physical.getRequestId());
    }

    @Test
    void overloadedProviderCountsAsFailedAttemptAndUsesNextNode() {
        AtomicInteger sends = new AtomicInteger();
        RpcCallExecutor executor = new RpcCallExecutor(config(1000, 2),
                discovery(request -> List.of(FIRST, SECOND)), (physical, address, remaining) -> {
                    sends.incrementAndGet();
                    if (FIRST.equals(address)) {
                        return RpcResponse.fail(RpcStatusCode.OVERLOADED, physical.getRequestId());
                    }
                    return RpcResponse.success("accepted", physical.getRequestId());
                });

        RpcResponse<Object> reply = executor.execute(request("logical-overload", true));
        assertEquals(2, sends.get());
        assertEquals("accepted", reply.getData());
        assertEquals("logical-overload", reply.getRequestId());

        RpcCallExecutor nonIdempotent = new RpcCallExecutor(config(1000, 2),
                discovery(request -> List.of(FIRST, SECOND)), (physical, address, remaining) -> {
                    return RpcResponse.fail(RpcStatusCode.OVERLOADED, physical.getRequestId());
                });
        RpcRejectedException rejected = assertThrows(RpcRejectedException.class,
                () -> nonIdempotent.execute(request("write-overload", false)));
        assertEquals(RpcStatusCode.OVERLOADED.getCode(), rejected.getCode());
        assertEquals("write-overload", rejected.getRequestId());
    }

    @Test
    void overloadedReplyOpensTheProviderCircuit() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("rpc-overload-circuit-test",
                Map.of("rpc.client.timeout-ms", 1000, "rpc.client.max-attempts", 1,
                        "rpc.client.circuit.minimum-calls", 1, "rpc.client.circuit.window-size", 1)));
        RpcConfiguration.configure(environment);
        AtomicInteger sends = new AtomicInteger();
        RpcCallExecutor executor = new RpcCallExecutor(RpcConfiguration.current(),
                discovery(request -> List.of(FIRST)), (physical, address, remaining) -> {
                    sends.incrementAndGet();
                    return RpcResponse.fail(RpcStatusCode.OVERLOADED, physical.getRequestId());
                });

        RpcRejectedException overloaded = assertThrows(RpcRejectedException.class,
                () -> executor.execute(request("first-overload", false)));
        RpcRejectedException circuitOpen = assertThrows(RpcRejectedException.class,
                () -> executor.execute(request("second-overload", false)));
        assertEquals(RpcStatusCode.OVERLOADED.getCode(), overloaded.getCode());
        assertEquals(RpcStatusCode.CALL_REJECTED.getCode(), circuitOpen.getCode());
        assertEquals(1, sends.get());
    }

    @Test
    void physicalRejectionRetainsSubclassAndCauseAtLogicalBoundary() {
        RpcCallExecutor nonIdempotent = new RpcCallExecutor(config(1000, 2),
                discovery(request -> List.of(FIRST, SECOND)), (physical, address, remaining) -> {
                    throw new RpcRejectedException(RpcStatusCode.OVERLOADED.getCode(),
                            physical.getRequestId(), "provider overloaded");
                });
        RpcRejectedException firstFailure = assertThrows(RpcRejectedException.class,
                () -> nonIdempotent.execute(request("logical-write", false)));
        assertEquals(RpcStatusCode.OVERLOADED.getCode(), firstFailure.getCode());
        assertEquals("logical-write", firstFailure.getRequestId());
        RpcRejectedException physical = assertInstanceOf(RpcRejectedException.class, firstFailure.getCause());
        assertNotEquals("logical-write", physical.getRequestId());

        AtomicInteger sends = new AtomicInteger();
        RpcCallExecutor exhausted = new RpcCallExecutor(config(1000, 2),
                discovery(request -> List.of(FIRST, SECOND)), (attempt, address, remaining) -> {
                    sends.incrementAndGet();
                    throw new RpcRejectedException(RpcStatusCode.OVERLOADED.getCode(),
                            attempt.getRequestId(), "provider overloaded");
                });
        RpcRejectedException lastFailure = assertThrows(RpcRejectedException.class,
                () -> exhausted.execute(request("logical-read", true)));
        assertEquals(2, sends.get());
        assertEquals(RpcStatusCode.OVERLOADED.getCode(), lastFailure.getCode());
        assertEquals("logical-read", lastFailure.getRequestId());
        assertInstanceOf(RpcRejectedException.class, lastFailure.getCause());
    }

    @Test
    void legacyLocalFailuresGainConservativeCodesAndLogicalId() {
        RpcCallExecutor timeout = new RpcCallExecutor(config(1000, 1),
                discovery(request -> List.of(FIRST)), (physical, address, remaining) -> {
                    throw new RpcTimeoutException("legacy timeout", null);
                });
        RpcTimeoutException timedOut = assertThrows(RpcTimeoutException.class,
                () -> timeout.execute(request("logical-old-timeout", false)));
        assertEquals(RpcStatusCode.RESPONSE_TIMEOUT.getCode(), timedOut.getCode());
        assertEquals("logical-old-timeout", timedOut.getRequestId());
        assertInstanceOf(RpcTimeoutException.class, timedOut.getCause());

        RpcCallExecutor unavailable = new RpcCallExecutor(config(1000, 1),
                discovery(request -> List.of(FIRST)), (physical, address, remaining) -> {
                    throw new RpcUnavailableException("legacy connection loss");
                });
        RpcUnavailableException lost = assertThrows(RpcUnavailableException.class,
                () -> unavailable.execute(request("logical-old-unavailable", false)));
        assertEquals(RpcStatusCode.CONNECTION_LOST.getCode(), lost.getCode());
        assertEquals("logical-old-unavailable", lost.getRequestId());
        assertInstanceOf(RpcUnavailableException.class, lost.getCause());
    }

    @Test
    void interruptedUnavailableCallDoesNotRetry() {
        AtomicInteger sends = new AtomicInteger();
        RpcCallExecutor executor = new RpcCallExecutor(config(1000, 2),
                discovery(request -> List.of(FIRST, SECOND)), (physical, address, remaining) -> {
                    sends.incrementAndGet();
                    throw new RpcUnavailableException(RpcStatusCode.CALL_INTERRUPTED,
                            physical.getRequestId(), "interrupted", new InterruptedException());
                });

        RpcUnavailableException interrupted = assertThrows(RpcUnavailableException.class,
                () -> executor.execute(request("logical-interrupted", true)));
        assertEquals(1, sends.get());
        assertEquals(RpcStatusCode.CALL_INTERRUPTED.getCode(), interrupted.getCode());
        assertEquals("logical-interrupted", interrupted.getRequestId());
    }

    private static RpcRequest request(String requestId, boolean idempotent) {
        return RpcRequest.builder().requestId(requestId).interfaceName("example.EchoService")
                .methodName("echo").parameters(new Object[0]).paramTypes(new Class<?>[0])
                .group("").version("").idempotent(idempotent).build();
    }

    private interface Lookup {
        List<InetSocketAddress> get(RpcRequest request);
    }

    private static ServiceDiscovery discovery(Lookup lookup) {
        return new ServiceDiscovery() {
            @Override
            public InetSocketAddress lookupService(RpcRequest request) {
                return lookup.get(request).get(0);
            }

            @Override
            public List<InetSocketAddress> lookupServices(RpcRequest request) {
                return lookup.get(request);
            }
        };
    }

    private static RpcConfiguration config(int timeoutMs, int attempts) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("rpc-call-status-test",
                Map.of("rpc.client.timeout-ms", timeoutMs, "rpc.client.max-attempts", attempts)));
        RpcConfiguration.configure(environment);
        return RpcConfiguration.current();
    }
}
