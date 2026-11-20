package io.github.backoutside.rivet.dianping.provider;

import io.github.backoutside.rivet.annotation.RpcService;
import io.github.backoutside.rivet.dianping.api.OrderDto;
import io.github.backoutside.rivet.dianping.api.SeckillResult;
import io.github.backoutside.rivet.dianping.api.VoucherDto;
import io.github.backoutside.rivet.dianping.api.VoucherOrderService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RpcService
public class VoucherOrderServiceImpl implements VoucherOrderService {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<String> seckillScript;

    public VoucherOrderServiceImpl(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.seckillScript = new DefaultRedisScript<>();
        this.seckillScript.setLocation(new ClassPathResource("lua/seckill.lua"));
        this.seckillScript.setResultType(String.class);
    }

    @Override
    public List<VoucherDto> listVouchers(long shopId) {
        if (shopId <= 0) {
            return List.of();
        }
        return jdbc.query("SELECT id, shop_id, title, face_value_cents, " +
                        "starts_at_epoch_millis, ends_at_epoch_millis FROM vouchers WHERE shop_id = ? ORDER BY id",
                (rs, row) -> new VoucherDto(rs.getLong("id"), rs.getLong("shop_id"),
                        rs.getString("title"), rs.getLong("face_value_cents"),
                        rs.getLong("starts_at_epoch_millis"), rs.getLong("ends_at_epoch_millis")), shopId);
    }

    @Override
    public SeckillResult seckill(long voucherId, long userId) {
        if (voucherId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("voucherId and userId must be positive");
        }
        String orderId = UUID.randomUUID().toString();
        String scriptResult = redis.execute(seckillScript,
                List.of(VoucherKeys.stock(voucherId), VoucherKeys.accepted(voucherId, userId),
                        VoucherKeys.metadata(voucherId), VoucherKeys.order(orderId), VoucherKeys.STREAM),
                Long.toString(userId), orderId, Long.toString(voucherId),
                Long.toString(System.currentTimeMillis()));
        if (scriptResult == null) {
            throw new IllegalStateException("Seckill script returned no result");
        }
        if (scriptResult.startsWith("ACCEPTED:")) {
            return new SeckillResult("ACCEPTED", scriptResult.substring("ACCEPTED:".length()));
        }
        switch (scriptResult) {
            case "SOLD_OUT":
            case "NOT_STARTED":
            case "ENDED":
            case "NOT_FOUND":
                return new SeckillResult(scriptResult, null);
            default:
                throw new IllegalStateException("Unknown seckill result: " + scriptResult);
        }
    }

    @Override
    public OrderDto findOrder(String orderId, long userId) {
        if (orderId == null || orderId.isBlank() || userId <= 0) {
            return null;
        }
        List<OrderDto> persisted = jdbc.query(
                "SELECT order_id, voucher_id, status FROM voucher_orders WHERE order_id = ? AND user_id = ?",
                (rs, row) -> new OrderDto(rs.getString("order_id"), rs.getLong("voucher_id"),
                        rs.getString("status")), orderId, userId);
        if (!persisted.isEmpty()) {
            return persisted.get(0);
        }
        Map<Object, Object> pending = redis.opsForHash().entries(VoucherKeys.order(orderId));
        if (!Long.toString(userId).equals(pending.get("userId"))) {
            return null;
        }
        long voucherId = Long.parseLong((String) pending.get("voucherId"));
        return new OrderDto(orderId, voucherId, "PENDING");
    }
}
