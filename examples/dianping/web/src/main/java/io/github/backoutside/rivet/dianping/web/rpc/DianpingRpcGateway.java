package io.github.backoutside.rivet.dianping.web.rpc;

import io.github.backoutside.rivet.annotation.RpcReference;
import io.github.backoutside.rivet.dianping.api.OrderDto;
import io.github.backoutside.rivet.dianping.api.SeckillResult;
import io.github.backoutside.rivet.dianping.api.ShopDto;
import io.github.backoutside.rivet.dianping.api.ShopQueryService;
import io.github.backoutside.rivet.dianping.api.VoucherDto;
import io.github.backoutside.rivet.dianping.api.VoucherOrderService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DianpingRpcGateway {
    @RpcReference
    private ShopQueryService shops;

    @RpcReference
    private VoucherOrderService orders;

    public ShopDto findShop(long shopId) {
        return shops.findShop(shopId);
    }

    public List<VoucherDto> listVouchers(long shopId) {
        return orders.listVouchers(shopId);
    }

    public SeckillResult seckill(long voucherId, long userId) {
        return orders.seckill(voucherId, userId);
    }

    public OrderDto findOrder(String orderId, long userId) {
        return orders.findOrder(orderId, userId);
    }
}
