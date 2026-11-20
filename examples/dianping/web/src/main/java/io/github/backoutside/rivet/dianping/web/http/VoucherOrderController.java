package io.github.backoutside.rivet.dianping.web.http;

import io.github.backoutside.rivet.dianping.api.OrderDto;
import io.github.backoutside.rivet.dianping.api.SeckillResult;
import io.github.backoutside.rivet.dianping.web.rpc.DianpingRpcGateway;
import io.github.backoutside.rivet.dianping.web.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class VoucherOrderController {
    private final DianpingRpcGateway rpc;
    private final SessionService sessions;

    public VoucherOrderController(DianpingRpcGateway rpc, SessionService sessions) {
        this.rpc = rpc;
        this.sessions = sessions;
    }

    @PostMapping("/vouchers/{id}/seckill")
    public ResponseEntity<SeckillResult> seckill(@PathVariable long id,
                                                 @RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @RequestBody(required = false) String body) {
        if (id <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ID", "Voucher id must be positive");
        }
        if (body != null && !body.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNEXPECTED_BODY", "This request does not accept a body");
        }
        long userId = sessions.requireUserId(authorization);
        SeckillResult result = rpc.seckill(id, userId);
        if (result == null || result.getStatus() == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_BAD_RESPONSE", "Invalid upstream response");
        }
        return switch (result.getStatus()) {
            case "ACCEPTED" -> ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
            case "SOLD_OUT", "NOT_STARTED", "ENDED" -> throw new ApiException(
                    HttpStatus.CONFLICT, result.getStatus(), "Voucher cannot be ordered");
            case "NOT_FOUND" -> throw new ApiException(HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND", "Voucher not found");
            default -> throw new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_BAD_RESPONSE", "Invalid upstream response");
        };
    }

    @GetMapping("/orders/{orderId}")
    public OrderDto findOrder(@PathVariable String orderId,
                              @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = sessions.requireUserId(authorization);
        OrderDto order = rpc.findOrder(orderId, userId);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
        }
        return order;
    }
}
