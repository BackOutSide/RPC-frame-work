package io.github.backoutside.rivet.dianping.probe;

import io.github.backoutside.rivet.annotation.RpcReference;
import io.github.backoutside.rivet.dianping.api.RpcProbeService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Profile("!fixture-provider & !fixture-consumer")
final class RpcProbe {
    private static final Logger LOG = LoggerFactory.getLogger(RpcProbe.class);
    private static final String SERVICE = RpcProbeService.class.getName();

    private final AtomicReference<Result> result = new AtomicReference<>(new Result(0, 0));

    @RpcReference
    private RpcProbeService target;

    RpcProbe(MeterRegistry registry) {
        Gauge.builder("rivet.rpc.probe.consecutive.failures", result, value -> value.get().failures())
                .description("Consecutive failed RPC probes since the last successful probe")
                .tag("service", SERVICE)
                .register(registry);
        TimeGauge.builder("rivet.rpc.probe.last.completed.timestamp", result, TimeUnit.SECONDS,
                        value -> value.get().completedAt())
                .description("Unix timestamp of the last completed RPC probe, or zero before the first result")
                .tag("service", SERVICE)
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${dianping.probe.interval-ms:15000}")
    void poll() {
        poll(target, Clock.systemUTC());
    }

    void poll(RpcProbeService service, Clock clock) {
        boolean success = responds(service);
        long completedAt = clock.instant().getEpochSecond();
        result.updateAndGet(previous -> new Result(success ? 0 : previous.failures() + 1, completedAt));
    }

    private boolean responds(RpcProbeService service) {
        try {
            return RpcProbeService.PONG.equals(service.ping());
        } catch (RuntimeException ex) {
            LOG.debug("RPC probe failed for {}", SERVICE, ex);
            return false;
        }
    }

    private record Result(long failures, long completedAt) {
    }
}
