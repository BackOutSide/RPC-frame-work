package io.github.backoutside.rivet.dianping.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class VoucherStateInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(VoucherStateInitializer.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public VoucherStateInitializer(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.query("SELECT id, stock_remaining, starts_at_epoch_millis, ends_at_epoch_millis FROM vouchers",
                rs -> {
                    long voucherId = rs.getLong("id");
                    // SETNX preserves reservations across provider restarts.
                    redis.opsForValue().setIfAbsent(VoucherKeys.stock(voucherId),
                            Integer.toString(rs.getInt("stock_remaining")));
                    String metadataKey = VoucherKeys.metadata(voucherId);
                    redis.opsForHash().putIfAbsent(metadataKey, "startsAt",
                            Long.toString(rs.getLong("starts_at_epoch_millis")));
                    redis.opsForHash().putIfAbsent(metadataKey, "endsAt",
                            Long.toString(rs.getLong("ends_at_epoch_millis")));
                });

        // A Redis rebuild must remember already confirmed users. This is bounded by
        // active/demo vouchers here; larger deployments need a dedicated restore job.
        long now = System.currentTimeMillis();
        jdbc.query("SELECT o.order_id, o.voucher_id, o.user_id, v.ends_at_epoch_millis " +
                        "FROM voucher_orders o JOIN vouchers v ON v.id = o.voucher_id " +
                        "WHERE v.ends_at_epoch_millis > ?", rs -> {
                    long forgetAfter = rs.getLong("ends_at_epoch_millis") + Duration.ofDays(7).toMillis();
                    long ttlMillis = forgetAfter - System.currentTimeMillis();
                    if (ttlMillis > 0) {
                        redis.opsForValue().setIfAbsent(
                                VoucherKeys.accepted(rs.getLong("voucher_id"), rs.getLong("user_id")),
                                rs.getString("order_id"), Duration.ofMillis(ttlMillis));
                    }
                }, now - Duration.ofDays(7).toMillis());

        try {
            // Offset zero also picks up orders queued before a group was created.
            redis.opsForStream().createGroup(VoucherKeys.STREAM, ReadOffset.from("0"), VoucherKeys.GROUP);
            log.info("Created Redis order consumer group");
        } catch (DataAccessException e) {
            if (!isExistingGroup(e)) {
                throw e;
            }
        }
    }

    private static boolean isExistingGroup(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (String.valueOf(cause.getMessage()).contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }
}
