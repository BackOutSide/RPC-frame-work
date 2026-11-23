package io.github.backoutside.rivet.exception;

import io.github.backoutside.rivet.enums.RpcStatusCode;

public class RpcUnavailableException extends RpcException {
    public RpcUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public RpcUnavailableException(String message) {
        super(message);
    }

    public RpcUnavailableException(RpcStatusCode statusCode, String requestId, String message, Throwable cause) {
        super(statusCode, requestId, message, cause);
    }
}
