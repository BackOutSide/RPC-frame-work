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
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcCallExecutorTest {
    private static final InetSocketAddress FIRST = InetSocketAddress.createUnresolved("provider-a", 19001);
    private static final InetSocketAddress SECOND = InetSocketAddress.createUnresolved("provider-b", 19002);

    @AfterEach
    void resetConfiguration() {
        RpcConfiguration.configure(new StandardEnvironment());
    }

    @Test
    void idempotentCallRetriesOnAnotherProviderWithDistinctPhysicalIds() {
        List<InetSocketAddress> visited = new ArrayList<>();
        List<String> physicalIds = new ArrayList<>();
        RpcCallExecutor executor = new RpcCallExecutor(config(1000, 2, 10, 20, 5000),
                discovery(FIRST, SECOND), (request, address, remaining) -> {
                    visited.add(address);
                    physicalIds.add(request.getRequestId());
                    assertTrue(remaining > 0);
                    if (address.equals(FIRST)) {
                        throw new RpcUnavailableException("connection failed");
                    }
                    return RpcResponse.success("second-provider", request.getRequestId());
                });

        RpcRequest logical = request("logical-id", true);
        RpcResponse<Object> response = executor.execute(logical);

        assertEquals(List.of(FIRST, SECOND), visited);
        assertEquals(2, physicalIds.size());
        assertNotEquals(logical.getRequestId(), physicalIds.get(0));
        assertNotEquals(physicalIds.get(0), physicalIds.get(1));
        assertEquals("logical-id", response.getRequestId());
        assertEquals("second-provider", response.getData());
        assertEquals("logical-id", logical.getRequestId());
    }

    @Test
    void nonIdempotentCallDoesNotReplayAfterUncertainFailure() {
        List<InetSocketAddress> visited = new ArrayList<>();
        RpcCallExecutor executor = new RpcCallExecutor(config(1000, 2, 10, 20, 5000),
                discovery(FIRST, SECOND), (request, address, remaining) -> {
                    visited.add(address);
                    throw new RpcUnavailableException("connection lost after send");
                });

        assertThrows(RpcUnavailableException.class, () -> executor.execute(request("write-id", false)));
        assertEquals(List.of(FIRST), visited);
    }

    @Test
    void lateFirstAttemptResponseCannotCompleteSecondAttempt() {
        UnprocessedRequests pending = new UnprocessedRequests();
        EmbeddedChannel channel = new EmbeddedChannel();
        List<String> physicalIds = new ArrayList<>();
        try {
            RpcCallExecutor executor = new RpcCallExecutor(config(1000, 2, 10, 20, 5000),
                    discovery(FIRST, SECOND), (request, address, remaining) -> {
                        physicalIds.add(request.getRequestId());
                        CompletableFuture<RpcResponse<Object>> future = new CompletableFuture<>();
                        pending.put(request.getRequestId(), channel, future);
                        if (address.equals(FIRST)) {
                            pending.remove(request.getRequestId());
                            throw new RpcTimeoutException("first attempt timed out", null);
                        }
                        pending.complete(RpcResponse.success("late-first", physicalIds.get(0)));
                        assertFalse(future.isDone(), "late reply must not complete a later attempt");
                        pending.complete(RpcResponse.success("second", request.getRequestId()));
                        return future.join();
                    });

            RpcResponse<Object> response = executor.execute(request("logical-late", true));
            assertEquals(2, physicalIds.size());
            assertNotEquals(physicalIds.get(0), physicalIds.get(1));
            assertEquals("second", response.getData());
            assertEquals("logical-late", response.getRequestId());
            assertEquals(0, pending.size());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void deadlineReturnsBeforeBlockedDiscoveryFinishesAndPreventsAnyAttempt() throws InterruptedException {
        AtomicInteger sends = new AtomicInteger();
        CountDownLatch discoveryStarted = new CountDownLatch(1);
        CountDownLatch releaseDiscovery = new CountDownLatch(1);
        CountDownLatch discoveryFinished = new CountDownLatch(1);
        ServiceDiscovery slowDiscovery = new ServiceDiscovery() {
            @Override
            public InetSocketAddress lookupService(RpcRequest request) {
                return FIRST;
            }

            @Override
            public List<InetSocketAddress> lookupServices(RpcRequest request) {
                discoveryStarted.countDown();
                try {
                    while (releaseDiscovery.getCount() > 0) {
                        try {
                            releaseDiscovery.await();
                        } catch (InterruptedException ignored) {
                            // Model a ZooKeeper call that does not stop on Future.cancel(true).
                        }
                    }
                    return List.of(FIRST);
                } finally {
                    discoveryFinished.countDown();
                }
            }
        };
        RpcCallExecutor executor = new RpcCallExecutor(config(500, 2, 10, 20, 5000),
                slowDiscovery, (request, address, remaining) -> {
                    sends.incrementAndGet();
                    return RpcResponse.success("unexpected", request.getRequestId());
                });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                executor.execute(request("expired", true));
            } catch (Throwable ex) {
                failure.set(ex);
            }
        }, "rpc-discovery-deadline-test");
        try {
            caller.start();
            assertTrue(discoveryStarted.await(1, TimeUnit.SECONDS), "discovery did not start");
            caller.join(1500);
            assertFalse(caller.isAlive(), "logical call must end while discovery is still blocked");
            assertTrue(failure.get() instanceof RpcTimeoutException);
            assertEquals(1, discoveryFinished.getCount(), "discovery should still be blocked");
            assertEquals(0, sends.get());
        } finally {
            releaseDiscovery.countDown();
            caller.join(1500);
            assertTrue(discoveryFinished.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptedAttemptIsNeverRetriedEvenForIdempotentCall() {
        AtomicInteger sends = new AtomicInteger();
        RpcCallExecutor executor = new RpcCallExecutor(config(1000, 2, 10, 20, 5000),
                discovery(FIRST, SECOND), (request, address, remaining) -> {
                    sends.incrementAndGet();
                    Thread.currentThread().interrupt();
                    throw new RpcException(RpcStatusCode.CALL_INTERRUPTED, request.getRequestId(),
                            "waiting for reply was interrupted", new InterruptedException());
                });
        try {
            RpcException failure = assertThrows(RpcException.class,
                    () -> executor.execute(request("interrupted", true)));
            assertEquals(RpcStatusCode.CALL_INTERRUPTED, failure.getKnownStatusCode());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, sends.get(), "interruption must not switch to the second provider");
        } finally {
            Thread.interrupted(); // Clear the test runner thread's interrupt status.
        }
    }

    @Test
    void openCircuitSkipsProviderAndHalfOpenAllowsProbe() throws InterruptedException {
        AtomicInteger sends = new AtomicInteger();
        RpcCallExecutor executor = new RpcCallExecutor(config(1000, 1, 1, 1, 100),
                discovery(FIRST), (request, address, remaining) -> {
                    if (sends.incrementAndGet() == 1) {
                        throw new RpcUnavailableException("provider down");
                    }
                    return RpcResponse.success("recovered", request.getRequestId());
                });

        assertThrows(RpcUnavailableException.class, () -> executor.execute(request("first", false)));
        assertThrows(RpcRejectedException.class, () -> executor.execute(request("second", false)));
        assertEquals(1, sends.get(), "open circuit must reject without invoking the provider");

        Thread.sleep(150);
        assertEquals("recovered", executor.execute(request("third", false)).getData());
        assertEquals(2, sends.get());
    }

    private static RpcRequest request(String id, boolean idempotent) {
        return RpcRequest.builder()
                .requestId(id).interfaceName("example.EchoService").methodName("echo")
                .group("").version("").parameters(new Object[0]).paramTypes(new Class<?>[0])
                .idempotent(idempotent).build();
    }

    private static ServiceDiscovery discovery(InetSocketAddress... addresses) {
        List<InetSocketAddress> ordered = List.of(addresses);
        return new ServiceDiscovery() {
            @Override
            public InetSocketAddress lookupService(RpcRequest request) {
                return ordered.get(0);
            }

            @Override
            public List<InetSocketAddress> lookupServices(RpcRequest request) {
                return ordered;
            }
        };
    }

    private static RpcConfiguration config(int timeoutMs, int maxAttempts,
                                           int minimumCalls, int windowSize, int openMs) {
        Map<String, Object> values = new HashMap<>();
        values.put("rpc.client.timeout-ms", timeoutMs);
        values.put("rpc.client.max-attempts", maxAttempts);
        values.put("rpc.client.circuit.minimum-calls", minimumCalls);
        values.put("rpc.client.circuit.window-size", windowSize);
        values.put("rpc.client.circuit.open-ms", openMs);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("rpc-call-executor-test", values));
        RpcConfiguration.configure(environment);
        return RpcConfiguration.current();
    }
}
