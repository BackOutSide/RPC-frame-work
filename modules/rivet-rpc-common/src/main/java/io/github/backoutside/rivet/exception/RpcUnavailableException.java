package io.github.backoutside.rivet.exception;

public class RpcUnavailableException extends RpcException {
    public RpcUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public RpcUnavailableException(String message) {
        super(message);
    }
}
