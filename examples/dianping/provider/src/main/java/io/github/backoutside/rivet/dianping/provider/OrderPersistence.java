package io.github.backoutside.rivet.dianping.provider;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderPersistence {
    private final JdbcTemplate jdbc;

    public OrderPersistence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void persist(String orderId, long voucherId, long userId) {
        List<String> existing = jdbc.query(
                "SELECT order_id FROM voucher_orders WHERE voucher_id = ? AND user_id = ?",
                (rs, row) -> rs.getString("order_id"), voucherId, userId);
        if (!existing.isEmpty()) {
            if (orderId.equals(existing.get(0))) {
                // A previous transaction committed but its Stream XACK was lost.
                return;
            }
            throw new IllegalStateException("A different order already exists for voucher " +
                    voucherId + " and user " + userId);
        }

        int updated = jdbc.update(
                "UPDATE vouchers SET stock_remaining = stock_remaining - 1 " +
                        "WHERE id = ? AND stock_remaining > 0", voucherId);
        if (updated != 1) {
            throw new IllegalStateException("Database stock exhausted for voucher " + voucherId);
        }
        jdbc.update("INSERT INTO voucher_orders (order_id, voucher_id, user_id, status) " +
                "VALUES (?, ?, ?, 'CONFIRMED')", orderId, voucherId, userId);
    }
}
