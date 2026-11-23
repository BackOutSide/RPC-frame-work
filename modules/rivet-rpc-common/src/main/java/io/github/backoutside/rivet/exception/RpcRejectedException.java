package io.github.backoutside.rivet.exception;

import io.github.backoutside.rivet.enums.RpcStatusCode;

/** A local bulkhead/circuit or remote overload rejected the RPC before execution. */
public class RpcRejectedException extends RpcException {
    public RpcRejectedException(String message) {
        super(RpcStatusCode.CALL_REJECTED, null, message, null);
    }

    public RpcRejectedException(int code, String requestId, String message) {
        this(code, requestId, message, null);
    }

    public RpcRejectedException(int code, String requestId, String message, Throwable cause) {
        super(code, requestId, message, cause);
    }
}
