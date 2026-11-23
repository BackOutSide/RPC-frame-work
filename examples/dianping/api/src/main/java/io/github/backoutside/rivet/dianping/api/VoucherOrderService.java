package io.github.backoutside.rivet.dianping.api;

import io.github.backoutside.rivet.annotation.RpcIdempotent;
import java.util.List;

public interface VoucherOrderService {
    @RpcIdempotent
    List<VoucherDto> listVouchers(long shopId);

    /** Returns the original order ID when this user has already been accepted. */
    SeckillResult seckill(long voucherId, long userId);

    /** Returns null when the order does not exist or belongs to another user. */
    @RpcIdempotent
    OrderDto findOrder(String orderId, long userId);
}
