package io.github.backoutside.rivet.remoting.dto;

import io.github.backoutside.rivet.enums.RpcResponseCodeEnum;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import lombok.*;

import java.io.Serializable;

/**
 * RPC响应实体
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class RpcResponse<T> implements Serializable {

    // 序列化ID
    private static final long serialVersionUID = 715745410605631233L;
    // 请求ID
    private String requestId;
    /**
     * response code
     */
    private Integer code;
    /**
     * response message
     */
    private String message;
    /**
     * response body
     */
    private T data;

    /**
     * 成功响应
     * @param data
     * @param requestId
     * @param <T>
     * @return
     */
    public static <T> RpcResponse<T> success(T data, String requestId) {
        RpcResponse<T> response = new RpcResponse<>();
        response.setCode(RpcResponseCodeEnum.SUCCESS.getCode());
        response.setMessage(RpcResponseCodeEnum.SUCCESS.getMessage());
        response.setRequestId(requestId);
        if (null != data) {
            response.setData(data);
        }
        return response;
    }

    /**
     * 失败响应
     * @param rpcResponseCodeEnum
     * @param <T>
     * @return
     */
    public static <T> RpcResponse<T> fail(RpcResponseCodeEnum rpcResponseCodeEnum) {
        RpcResponse<T> response = new RpcResponse<>();
        response.setCode(rpcResponseCodeEnum.getCode());
        response.setMessage(rpcResponseCodeEnum.getMessage());
        return response;
    }

    public static <T> RpcResponse<T> fail(RpcResponseCodeEnum code, String requestId, String message) {
        RpcResponse<T> response = fail(code);
        response.setRequestId(requestId);
        response.setMessage(message == null ? code.getMessage() : message);
        return response;
    }

    /** Creates a correlated failure response for a status allowed on the wire. */
    public static <T> RpcResponse<T> fail(RpcStatusCode status, String requestId, String message) {
        if (status == null || !status.isWireResponse() || status == RpcStatusCode.SUCCESS) {
            throw new IllegalArgumentException("Status cannot be sent in an RPC failure response");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("A failure response requires a request ID");
        }
        RpcResponse<T> response = new RpcResponse<>();
        response.setCode(status.getCode());
        response.setMessage(message == null ? status.getMessage() : message);
        response.setRequestId(requestId);
        return response;
    }

    public static <T> RpcResponse<T> fail(RpcStatusCode status, String requestId) {
        return fail(status, requestId, null);
    }

}
