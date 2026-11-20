package io.github.backoutside.rivet.exception;

public class RpcTimeoutException extends RpcException {
    public RpcTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
