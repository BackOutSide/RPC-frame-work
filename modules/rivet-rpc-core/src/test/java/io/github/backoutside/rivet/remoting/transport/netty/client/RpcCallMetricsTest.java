package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.github.backoutside.rivet.annotation.RpcIdempotent;
import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcRejectedException;
import io.github.backoutside.rivet.exception.RpcUnavailableException;
import io.github.backoutside.rivet.proxy.RpcClientProxy;
import io.github.backoutside.rivet.registry.ServiceDiscovery;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks logical-call meters through the proxy and physical-attempt meters through the coordinator. */
class RpcCallMetricsTest {
    private static final InetSocketAddress FIRST = InetSocketAddress.createUnresolved("provider-a", 19001);
    private static final InetSocketAddress SECOND = InetSocketAddress.createUnresolved("provider-b", 19002);
    private static final AtomicInteger NEXT_METRIC_GROUP = new AtomicInteger();

    private SimpleMeterRegistry metrics;
    private String group;
    private String serviceName;

    interface EchoService {
        @RpcIdempotent
        String read();

        String write();
    }

    @BeforeEach
    void installMetrics() {
        group = "metrics-" + NEXT_METRIC_GROUP.incrementAndGet();
        serviceName = EchoService.class.getName() + group;
        metrics = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(metrics);
    }

    @AfterEach
    void restoreConfigurationAndMetrics() {
        Metrics.globalRegistry.remove(metrics);
        metrics.close();
        RpcConfiguration.configure(new StandardEnvironment());
    }

    @Test
    void retrySuccessIsOneLogicalCallWithTwoPhysicalAttempts() {
        AtomicInteger sends = new AtomicInteger();
        RpcCallExecutor executor = new RpcCallExecutor(config(2, 10, 20), discovery(FIRST, SECOND),
                (physical, address, remaining) -> {
                    sends.incrementAndGet();
                    if (FIRST.equals(address)) {
                        throw new RpcUnavailableException(RpcStatusCode.CONNECT_FAILED, physical.getRequestId(),
                                "first provider refused connection", null);
                    }
                    return RpcResponse.success("ok", physical.getRequestId());
                });

        assertEquals("ok", proxy(executor).read());

        assertEquals(2, sends.get());
        assertEquals(1, logicalCalls());
        assertEquals(1, logicalCallsWithOutcome("success"));
        assertEquals(1, counter("rivet.rpc.client.attempts", "outcome", "unavailable"));
        assertEquals(1, counter("rivet.rpc.client.attempts", "outcome", "success"));
        assertEquals(1, counter("rivet.rpc.client.retries"));
    }

    @Test
    void exhaustedProvidersAreOneTerminalLogicalFailure() {
        AtomicInteger sends = new AtomicInteger();
        RpcCallExecutor executor = new RpcCallExecutor(config(2, 10, 20), discovery(FIRST, SECOND),
                (physical, address, remaining) -> {
                    sends.incrementAndGet();
                    throw new RpcUnavailableException(RpcStatusCode.CONNECTION_LOST, physical.getRequestId(),
                            "connection lost", null);
                });

        assertThrows(RpcUnavailableException.class, () -> proxy(executor).read());

        assertEquals(2, sends.get());
        assertEquals(1, logicalCalls());
        assertEquals(1, logicalCallsWithOutcome("unavailable"));
        assertEquals(2, counter("rivet.rpc.client.attempts", "outcome", "unavailable"));
        assertEquals(1, counter("rivet.rpc.client.retries"));
    }

    @Test
    void openCircuitRejectsBeforeASecondPhysicalAttempt() {
        AtomicInteger sends = new AtomicInteger();
        RpcCallExecutor executor = new RpcCallExecutor(config(1, 1, 1), discovery(FIRST),
                (physical, address, remaining) -> {
                    sends.incrementAndGet();
                    throw new RpcUnavailableException(RpcStatusCode.CONNECT_FAILED, physical.getRequestId(),
                            "provider down", null);
                });
        EchoService service = proxy(executor);

        assertThrows(RpcUnavailableException.class, service::write);
        RpcRejectedException rejected = assertThrows(RpcRejectedException.class, service::write);

        assertEquals(RpcStatusCode.CALL_REJECTED, rejected.getKnownStatusCode());
        assertEquals(1, sends.get());
        assertEquals(2, logicalCalls());
        assertEquals(1, logicalCallsWithOutcome("unavailable"));
        assertEquals(1, logicalCallsWithOutcome("rejected"));
        assertEquals(1, counter("rivet.rpc.client.attempts", "outcome", "unavailable"));
        assertEquals(0, counter("rivet.rpc.client.retries"));
        assertEquals(1, counter("rivet.rpc.client.rejections", "reason", "circuit"));
    }

    private EchoService proxy(RpcCallExecutor executor) {
        RpcServiceConfig service = RpcServiceConfig.builder().group(group).version("").build();
        return new RpcClientProxy(executor::execute, service).getProxy(EchoService.class);
    }

    private long logicalCalls() {
        return metrics.getMeters().stream()
                .filter(meter -> meter instanceof Timer && "rivet.rpc.client.duration".equals(meter.getId().getName()))
                .filter(meter -> serviceName.equals(meter.getId().getTag("service")))
                .mapToLong(meter -> ((Timer) meter).count())
                .sum();
    }

    private long logicalCallsWithOutcome(String outcome) {
        Timer timer = metrics.find("rivet.rpc.client.duration")
                .tags("service", serviceName, "outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private double counter(String name, String... extraTags) {
        String[] tags = new String[extraTags.length + 2];
        tags[0] = "service";
        tags[1] = serviceName;
        System.arraycopy(extraTags, 0, tags, 2, extraTags.length);
        Counter counter = metrics.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private static ServiceDiscovery discovery(InetSocketAddress... addresses) {
        List<InetSocketAddress> candidates = List.of(addresses);
        return new ServiceDiscovery() {
            @Override
            public InetSocketAddress lookupService(RpcRequest request) {
                return candidates.get(0);
            }

            @Override
            public List<InetSocketAddress> lookupServices(RpcRequest request) {
                return candidates;
            }
        };
    }

    private static RpcConfiguration config(int maxAttempts, int minimumCalls, int windowSize) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("rpc-call-metrics-test",
                Map.of("rpc.client.timeout-ms", 1000, "rpc.client.max-attempts", maxAttempts,
                        "rpc.client.circuit.minimum-calls", minimumCalls,
                        "rpc.client.circuit.window-size", windowSize)));
        RpcConfiguration.configure(environment);
        return RpcConfiguration.current();
    }
}
