package io.github.backoutside.rivet.dianping.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OrderStreamWorker {
    private static final Logger log = LoggerFactory.getLogger(OrderStreamWorker.class);
    private static final Duration CLAIM_IDLE = Duration.ofSeconds(30);
    private final String consumerName = "provider-" + UUID.randomUUID();

    private final StringRedisTemplate redis;
    private final OrderPersistence persistence;

    public OrderStreamWorker(StringRedisTemplate redis, OrderPersistence persistence) {
        this.redis = redis;
        this.persistence = persistence;
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 100)
    public void receiveNewOrders() {
        try {
            StreamOperations<String, Object, Object> stream = redis.opsForStream();
            List<MapRecord<String, Object, Object>> records = stream.read(
                    Consumer.from(VoucherKeys.GROUP, consumerName),
                    StreamReadOptions.empty().count(20).block(Duration.ofMillis(100)),
                    StreamOffset.create(VoucherKeys.STREAM, ReadOffset.lastConsumed()));
            if (records != null) {
                records.forEach(this::process);
            }
        } catch (RuntimeException e) {
            log.error("Failed to poll the order stream", e);
        }
    }

    @Scheduled(initialDelay = 30000, fixedDelay = 5000)
    public void recoverPendingOrders() {
        try {
            StreamOperations<String, Object, Object> stream = redis.opsForStream();
            PendingMessages pending = stream.pending(VoucherKeys.STREAM, VoucherKeys.GROUP,
                    Range.unbounded(), 100);
            if (pending == null) {
                return;
            }
            for (PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery().compareTo(CLAIM_IDLE) < 0) {
                    continue;
                }
                List<MapRecord<String, Object, Object>> claimed = stream.claim(
                        VoucherKeys.STREAM, VoucherKeys.GROUP, consumerName,
                        CLAIM_IDLE, message.getId());
                if (claimed != null) {
                    claimed.forEach(this::process);
                }
            }
        } catch (RuntimeException e) {
            log.error("Failed to recover pending orders", e);
        }
    }

    private void process(MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> data = record.getValue();
            String orderId = required(data, "orderId");
            long voucherId = Long.parseLong(required(data, "voucherId"));
            long userId = Long.parseLong(required(data, "userId"));
            persistence.persist(orderId, voucherId, userId);
            // The transaction has committed when the proxied method returns.
            redis.opsForStream().acknowledge(VoucherKeys.STREAM, VoucherKeys.GROUP, record.getId());
        } catch (RuntimeException e) {
            // Leave this entry pending; a later claim retries it.
            log.error("Order stream entry {} failed and remains pending", record.getId(), e);
        }
    }

    private static String required(Map<Object, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof String) || ((String) value).isBlank()) {
            throw new IllegalArgumentException("Missing order stream field " + key);
        }
        return (String) value;
    }
}
