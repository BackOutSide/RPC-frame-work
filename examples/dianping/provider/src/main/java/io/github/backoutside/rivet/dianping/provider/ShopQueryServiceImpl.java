package io.github.backoutside.rivet.dianping.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.backoutside.rivet.annotation.RpcService;
import io.github.backoutside.rivet.dianping.api.ShopDto;
import io.github.backoutside.rivet.dianping.api.ShopQueryService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

@Service
@RpcService
public class ShopQueryServiceImpl implements ShopQueryService {
    private static final String NULL_MARKER = "#missing";
    private static final Duration NULL_TTL = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final long LOCK_WAIT_NANOS = Duration.ofSeconds(2).toNanos();
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = unlockScript();

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ShopQueryServiceImpl(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public ShopDto findShop(long shopId) {
        if (shopId <= 0) {
            return null;
        }
        String cacheKey = "dianping:shop:" + shopId;
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            return decode(cached);
        }

        // One provider rebuilds a cold key. Other callers wait for the cache value.
        String lockKey = cacheKey + ":lock";
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + LOCK_WAIT_NANOS;
        while (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL))) {
            cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return decode(cached);
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Shop cache rebuild timed out for shop " + shopId);
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }

        try {
            // Recheck after obtaining the lock: another node may have rebuilt the value.
            cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return decode(cached);
            }
            List<ShopDto> shops = jdbc.query(
                    "SELECT id, name, address, description FROM shops WHERE id = ?",
                    (rs, row) -> new ShopDto(rs.getLong("id"), rs.getString("name"),
                            rs.getString("address"), rs.getString("description")),
                    shopId);
            if (shops.isEmpty()) {
                redis.opsForValue().set(cacheKey, NULL_MARKER, NULL_TTL);
                return null;
            }
            ShopDto shop = shops.get(0);
            long ttlSeconds = 300 + ThreadLocalRandom.current().nextLong(60);
            redis.opsForValue().set(cacheKey, encode(shop), Duration.ofSeconds(ttlSeconds));
            return shop;
        } finally {
            redis.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
        }
    }

    private ShopDto decode(String value) {
        if (NULL_MARKER.equals(value)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            return new ShopDto(node.path("id").asLong(), node.path("name").asText(),
                    node.path("address").asText(), node.path("description").asText());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid shop cache value", e);
        }
    }

    private String encode(ShopDto shop) {
        try {
            return objectMapper.writeValueAsString(shop);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode shop cache value", e);
        }
    }

    private static DefaultRedisScript<Long> unlockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptSource(new org.springframework.scripting.support.StaticScriptSource(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('DEL', KEYS[1]) else return 0 end"));
        return script;
    }
}
