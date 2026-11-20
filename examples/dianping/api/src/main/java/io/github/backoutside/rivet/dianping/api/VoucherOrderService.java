package io.github.backoutside.rivet.dianping.api;

import java.util.List;

public interface VoucherOrderService {
    List<VoucherDto> listVouchers(long shopId);

    /** Returns the original order ID when this user has already been accepted. */
    SeckillResult seckill(long voucherId, long userId);

    /** Returns null when the order does not exist or belongs to another user. */
    OrderDto findOrder(String orderId, long userId);
}
