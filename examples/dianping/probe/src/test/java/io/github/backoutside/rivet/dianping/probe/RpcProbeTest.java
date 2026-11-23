package io.github.backoutside.rivet.dianping.probe;

import io.github.backoutside.rivet.dianping.api.RpcProbeService;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcProbeTest {
    private static final String STREAK = "rivet_rpc_probe_consecutive_failures";
    private static final String LAST_COMPLETED = "rivet_rpc_probe_last_completed_timestamp_seconds";
    private static final String SERVICE = RpcProbeService.class.getName();
    private static final long FIRST_SECOND = 1_800_000_000L;

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void exportsBothGaugesBeforeAnyProbeCompletes() {
        RpcProbe probe = new RpcProbe(registry);
        assertResult(0, 0);
        probe.poll(() -> RpcProbeService.PONG, at(FIRST_SECOND));
        assertResult(0, FIRST_SECOND);
    }

    @Test
    void exceptionsAndUnexpectedRepliesCountTowardTheSameFailureStreak() {
        RpcProbe probe = new RpcProbe(registry);
        probe.poll(() -> {
            throw new IllegalStateException("Injected failed RPC");
        }, at(FIRST_SECOND));
        assertResult(1, FIRST_SECOND);

        probe.poll(() -> "unexpected", at(FIRST_SECOND + 15));
        assertResult(2, FIRST_SECOND + 15);

        probe.poll(() -> null, at(FIRST_SECOND + 30));
        assertResult(3, FIRST_SECOND + 30);

        probe.poll(() -> RpcProbeService.PONG, at(FIRST_SECOND + 45));
        assertResult(0, FIRST_SECOND + 45);

        probe.poll(() -> null, at(FIRST_SECOND + 60));
        assertResult(1, FIRST_SECOND + 60);
    }

    @Test
    void aPendingProbeDoesNotRefreshTheLastCompletedTimestamp() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try {
            RpcProbe probe = new RpcProbe(registry);
            probe.poll(() -> RpcProbeService.PONG, at(FIRST_SECOND));

            Future<?> pending = executor.submit(() -> probe.poll(() -> {
                entered.countDown();
                try {
                    if (!finish.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Probe did not receive the test release signal");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
                return "unhealthy";
            }, at(FIRST_SECOND + 15)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertResult(0, FIRST_SECOND);
            finish.countDown();
            pending.get(5, TimeUnit.SECONDS);
            assertResult(1, FIRST_SECOND + 15);
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    private static Clock at(long epochSecond) {
        return Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC);
    }

    private void assertResult(long failures, long timestamp) {
        String scrape = registry.scrape();
        assertEquals((double) failures, sample(scrape, STREAK));
        assertEquals((double) timestamp, sample(scrape, LAST_COMPLETED));
    }

    private static double sample(String scrape, String metric) {
        String line = scrape.lines()
                .filter(value -> value.startsWith(metric + "{") && value.contains("service=\"" + SERVICE + "\""))
                .findFirst().orElseThrow(() -> new AssertionError("Missing metric " + metric + " in " + scrape));
        return Double.parseDouble(line.substring(line.indexOf('}') + 1).strip());
    }
}
