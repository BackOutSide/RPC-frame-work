package io.github.backoutside.rivet.exception;

import io.github.backoutside.rivet.enums.RpcStatusCode;

public class RpcTimeoutException extends RpcException {
    public RpcTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    public RpcTimeoutException(RpcStatusCode statusCode, String requestId, String message, Throwable cause) {
        super(statusCode, requestId, message, cause);
    }
}
