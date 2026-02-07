package github.javaguide.exception;

import github.javaguide.enums.RpcErrorMessageEnum;

/**
 * RPC异常定义
 */
public class RpcException extends RuntimeException {

    /**
     * 
     * @param rpcErrorMessageEnum
     * @param detail
     */
    public RpcException(RpcErrorMessageEnum rpcErrorMessageEnum, String detail) {
        super(rpcErrorMessageEnum.getMessage() + ":" + detail);
    }

    /**
     * 初始化RPC异常
     * @param message
     * @param cause
     */
    public RpcException(String message, Throwable cause) {
        super(message, cause);
    }

    public RpcException(RpcErrorMessageEnum rpcErrorMessageEnum) {
        super(rpcErrorMessageEnum.getMessage());
    }
}
