package io.github.backoutside.rivet.loadbalance.loadbalancer;

import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistentHashLoadBalanceTest {
    @Test
    void keepsIndependentRingsForDifferentServices() {
        ConsistentHashLoadBalanceNew balance = new ConsistentHashLoadBalanceNew();
        List<String> shops = Arrays.asList("shop-a:1001", "shop-b:1002");
        List<String> orders = Arrays.asList("order-a:2001", "order-b:2002");
        RpcRequest shopRequest = request("ShopService", "same-request");
        RpcRequest orderRequest = request("OrderService", "same-request");

        String firstShop = balance.selectServiceAddress(shops, shopRequest);
        assertTrue(shops.contains(firstShop));
        assertTrue(orders.contains(balance.selectServiceAddress(orders, orderRequest)));
        assertEquals(firstShop, balance.selectServiceAddress(shops, shopRequest));

        List<String> changedOrders = Arrays.asList("order-b:2002", "order-c:2003");
        assertTrue(changedOrders.contains(balance.selectServiceAddress(changedOrders, orderRequest)));
        assertEquals(firstShop, balance.selectServiceAddress(shops, shopRequest));
    }

    private RpcRequest request(String interfaceName, String requestId) {
        return RpcRequest.builder().interfaceName(interfaceName)
                .group("").version("").requestId(requestId).build();
    }
}
