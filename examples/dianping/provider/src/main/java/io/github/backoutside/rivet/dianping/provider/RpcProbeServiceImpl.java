package io.github.backoutside.rivet.dianping.provider;

import io.github.backoutside.rivet.annotation.RpcService;
import io.github.backoutside.rivet.dianping.api.RpcProbeService;

@RpcService
public class RpcProbeServiceImpl implements RpcProbeService {
    @Override
    public String ping() {
        return PONG;
    }
}
