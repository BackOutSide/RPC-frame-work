package io.github.backoutside.rivet.dianping.probe;

import io.github.backoutside.rivet.annotation.RpcReference;
import io.github.backoutside.rivet.dianping.api.RpcProbeService;
import io.github.backoutside.rivet.exception.RpcException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Generates on-demand RPCs in the regular Consumer metrics job during evaluation. */
@Profile("fixture-consumer & !fixture-provider")
@RestController
class FixtureRpcConsumer {
    @RpcReference
    private RpcProbeService service;

    @GetMapping("/rpc-evaluation/ping")
    ResponseEntity<String> ping() {
        try {
            if (RpcProbeService.PONG.equals(service.ping())) {
                return ResponseEntity.ok(RpcProbeService.PONG);
            }
        } catch (RpcException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("RPC unavailable");
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("Unexpected RPC probe reply");
    }
}
