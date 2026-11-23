package io.github.backoutside.rivet.exception;

import io.github.backoutside.rivet.enums.RpcStatusCode;

/** A failure response sent by a provider, including codes unknown to this client. */
public class RpcRemoteException extends RpcException {
    public RpcRemoteException(int code, String requestId, String message) {
        super(code, requestId,
                message == null || message.isBlank()
                        ? defaultMessage(code) : message, null);
    }

    private static String defaultMessage(int code) {
        RpcStatusCode known = RpcStatusCode.fromCode(code);
        return known == null ? "Remote RPC failed with code " + code : known.getMessage();
    }
}
