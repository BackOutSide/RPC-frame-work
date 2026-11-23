package io.github.backoutside.rivet.metrics;

import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.exception.RpcRejectedException;
import io.github.backoutside.rivet.exception.RpcTimeoutException;
import io.github.backoutside.rivet.exception.RpcUnavailableException;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.micrometer.core.instrument.Metrics;

import java.util.concurrent.TimeUnit;

/** Low-cardinality RPC metrics shared by Spring Boot and plain Java hosts. */
public final class RpcMetrics {
    private RpcMetrics() {
    }

    public static String outcome(Throwable failure) {
        if (failure instanceof RpcException rpcException) {
            RpcStatusCode code = rpcException.getKnownStatusCode();
            if (code == RpcStatusCode.CALL_INTERRUPTED) {
                return "cancelled";
            }
            if (code == RpcStatusCode.PROTOCOL_ERROR || code == RpcStatusCode.CODEC_ERROR
                    || code == RpcStatusCode.INVALID_RESPONSE) {
                return "protocol_error";
            }
        }
        if (failure instanceof RpcTimeoutException) {
            return "timeout";
        }
        if (failure instanceof RpcRejectedException) {
            return "rejected";
        }
        if (failure instanceof RpcUnavailableException) {
            return "unavailable";
        }
        return "remote_error";
    }

    public static String outcome(RpcResponse<?> response) {
        if (response == null) {
            return "unavailable";
        }
        if (Integer.valueOf(RpcStatusCode.SUCCESS.getCode()).equals(response.getCode())) {
            return "success";
        }
        if (Integer.valueOf(RpcStatusCode.OVERLOADED.getCode()).equals(response.getCode())) {
            return "rejected";
        }
        if (Integer.valueOf(RpcStatusCode.INTERNAL_ERROR.getCode()).equals(response.getCode())) {
            return "unavailable";
        }
        return "remote_error";
    }

    public static void client(String service, String method, String transport, String outcome, long nanos) {
        Metrics.timer("rivet.rpc.client.duration", "service", service, "method", method,
                "transport", transport, "outcome", outcome).record(nanos, TimeUnit.NANOSECONDS);
    }

    public static void server(String service, String method, String outcome, long nanos) {
        Metrics.timer("rivet.rpc.server.duration", "service", service, "method", method,
                "outcome", outcome).record(nanos, TimeUnit.NANOSECONDS);
    }

    public static void attempt(String service, String outcome) {
        Metrics.counter("rivet.rpc.client.attempts", "service", service, "outcome", outcome).increment();
    }

    public static void retry(String service) {
        Metrics.counter("rivet.rpc.client.retries", "service", service).increment();
    }

    public static void rejection(String service, String reason) {
        Metrics.counter("rivet.rpc.client.rejections", "service", service, "reason", reason).increment();
    }

    public static void serverRejection(String service) {
        Metrics.counter("rivet.rpc.server.rejections", "service", service).increment();
    }
}
