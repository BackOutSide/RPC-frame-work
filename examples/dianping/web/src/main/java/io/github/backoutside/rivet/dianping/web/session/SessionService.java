package io.github.backoutside.rivet.dianping.web.session;

import io.github.backoutside.rivet.dianping.web.http.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    private static final String SESSION_PREFIX = "dianping:session:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final DemoUsersProperties users;

    public SessionService(StringRedisTemplate redis, DemoUsersProperties users) {
        this.redis = redis;
        this.users = users;
    }

    public String login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CREDENTIALS", "Username and password are required");
        }
        DemoUsersProperties.Account account = users.getAccounts().get(username);
        if (account == null || account.getPassword() == null || !samePassword(account.getPassword(), password)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password");
        }
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        redis.opsForValue().set(SESSION_PREFIX + token, Long.toString(account.getId()), users.getSessionTtl());
        return token;
    }

    public long requireUserId(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw unauthorized();
        }
        String token = authorization.substring(7).trim();
        if (!token.matches("[A-Za-z0-9_-]{43}")) {
            throw unauthorized();
        }
        String userId = redis.opsForValue().get(SESSION_PREFIX + token);
        if (userId == null) {
            throw unauthorized();
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException invalidSession) {
            throw unauthorized();
        }
    }

    private static boolean samePassword(String configured, String given) {
        return MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A valid Bearer token is required");
    }
}
