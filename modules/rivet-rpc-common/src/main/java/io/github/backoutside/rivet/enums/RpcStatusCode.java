package io.github.backoutside.rivet.enums;

/**
 * Stable status codes for a single RPC call. Only wire-response codes may be
 * placed in {@code RpcResponse.code}; local codes describe failures observed
 * by the caller or by a protocol endpoint.
 */
public enum RpcStatusCode {
    SUCCESS(200, "The remote call succeeded", true),
    LEGACY_FAIL(500, "The remote call failed", true),
    /** Existing overload response used by the admission handler. */
    OVERLOADED(503, "RPC provider is overloaded", true),

    INVALID_REQUEST(1101, "Invalid RPC request", true),
    SERVICE_NOT_FOUND(1102, "RPC service not found", true),
    METHOD_NOT_FOUND(1103, "RPC method not found", true),

    NO_PROVIDER(2101, "No RPC provider is available", false),
    DISCOVERY_FAILED(2102, "RPC service discovery failed", false),
    CONNECT_FAILED(2103, "Could not connect to RPC provider", false),
    SEND_FAILED(2104, "Could not send RPC request", false),
    CONNECTION_LOST(2105, "RPC connection was lost", false),
    CALL_REJECTED(2106, "RPC call was rejected by local admission policy", false),

    CONNECT_TIMEOUT(3101, "RPC connection timed out", false),
    RESPONSE_TIMEOUT(3102, "RPC call deadline exceeded", false),
    CALL_INTERRUPTED(3103, "RPC call was interrupted", false),

    PROTOCOL_ERROR(4101, "Invalid RPC protocol message", false),
    CODEC_ERROR(4102, "RPC codec failed", false),
    INVALID_RESPONSE(4103, "Invalid RPC response", false),

    PROVIDER_EXCEPTION(5101, "RPC provider method failed", true),
    INTERNAL_ERROR(5102, "Internal RPC framework error", true);

    private final int code;
    private final String message;
    private final boolean wireResponse;

    RpcStatusCode(int code, String message, boolean wireResponse) {
        this.code = code;
        this.message = message;
        this.wireResponse = wireResponse;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public boolean isWireResponse() {
        return wireResponse;
    }

    /** Returns null for an unknown code so callers can retain its raw value. */
    public static RpcStatusCode fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (RpcStatusCode status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
