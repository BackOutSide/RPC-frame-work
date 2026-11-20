package io.github.backoutside.rivet.dianping.web.http;

import io.github.backoutside.rivet.dianping.api.ShopDto;
import io.github.backoutside.rivet.dianping.api.VoucherDto;
import io.github.backoutside.rivet.dianping.web.rpc.DianpingRpcGateway;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shops")
public class ShopController {
    private final DianpingRpcGateway rpc;

    public ShopController(DianpingRpcGateway rpc) {
        this.rpc = rpc;
    }

    @GetMapping("/{id}")
    public ShopDto findShop(@PathVariable long id) {
        requirePositive(id);
        ShopDto shop = rpc.findShop(id);
        if (shop == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND", "Shop not found");
        }
        return shop;
    }

    @GetMapping("/{id}/vouchers")
    public List<VoucherDto> listVouchers(@PathVariable long id) {
        requirePositive(id);
        List<VoucherDto> vouchers = rpc.listVouchers(id);
        return vouchers == null ? List.of() : vouchers;
    }

    private static void requirePositive(long id) {
        if (id <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ID", "Shop id must be positive");
        }
    }
}
