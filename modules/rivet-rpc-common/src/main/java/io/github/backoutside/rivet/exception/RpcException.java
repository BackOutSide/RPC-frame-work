package io.github.backoutside.rivet.exception;

import io.github.backoutside.rivet.enums.RpcErrorMessageEnum;
import io.github.backoutside.rivet.enums.RpcStatusCode;

/**
 * RPC异常定义
 */
public class RpcException extends RuntimeException {
    private final Integer code;
    private final String requestId;

    public RpcException(String message) {
        super(message);
        this.code = null;
        this.requestId = null;
    }


    /**
     * 
     * @param rpcErrorMessageEnum
     * @param detail
     */
    public RpcException(RpcErrorMessageEnum rpcErrorMessageEnum, String detail) {
        super(rpcErrorMessageEnum.getMessage() + ":" + detail);
        this.code = null;
        this.requestId = null;
    }

    /**
     * 初始化RPC异常
     * @param message
     * @param cause
     */
    public RpcException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
        this.requestId = null;
    }

    public RpcException(RpcErrorMessageEnum rpcErrorMessageEnum) {
        super(rpcErrorMessageEnum.getMessage());
        this.code = null;
        this.requestId = null;
    }

    public RpcException(RpcStatusCode statusCode, String requestId, String message, Throwable cause) {
        this(statusCode.getCode(), requestId,
                message == null ? statusCode.getMessage() : message, cause);
    }

    protected RpcException(Integer code, String requestId, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.requestId = requestId;
    }

    /** The original integer is retained even when this client does not know the code. */
    public Integer getCode() {
        return code;
    }

    public RpcStatusCode getKnownStatusCode() {
        return RpcStatusCode.fromCode(code);
    }

    public String getRequestId() {
        return requestId;
    }
}
