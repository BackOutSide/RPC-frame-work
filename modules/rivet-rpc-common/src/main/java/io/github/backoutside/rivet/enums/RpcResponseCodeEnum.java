package io.github.backoutside.rivet.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * @author shuang.kou
 * @createTime 2020年05月12日 16:24:00
 */
@AllArgsConstructor
@Getter
@ToString
public enum RpcResponseCodeEnum {

    SUCCESS(200, "The remote call is successful"),
    FAIL(500, "The remote call is fail"),
    OVERLOADED(503, "RPC provider is overloaded");
    private final int code;

    private final String message;

}
