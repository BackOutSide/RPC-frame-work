package io.github.backoutside.rivet.dianping.probe;

import io.github.backoutside.rivet.annotation.RpcService;
import io.github.backoutside.rivet.dianping.api.RpcProbeService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Exposes the probe contract from a real Netty provider without business storage. */
@Profile("fixture-provider & !fixture-consumer")
@Component
@RpcService
public class FixtureRpcProvider implements RpcProbeService {
    @Override
    public String ping() {
        return PONG;
    }
}
