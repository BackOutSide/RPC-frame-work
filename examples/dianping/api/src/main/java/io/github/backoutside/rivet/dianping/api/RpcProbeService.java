package io.github.backoutside.rivet.dianping.api;

import io.github.backoutside.rivet.annotation.RpcIdempotent;

public interface RpcProbeService {
    String PONG = "pong";

    @RpcIdempotent
    String ping();
}
