package io.github.backoutside.rivet.dianping.web.http;

import io.github.backoutside.rivet.dianping.web.session.SessionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping
    public TokenResponse login(@RequestBody(required = false) LoginRequest request) {
        return new TokenResponse(sessions.login(
                request == null ? null : request.username(),
                request == null ? null : request.password()));
    }

    public record LoginRequest(String username, String password) {
    }

    public record TokenResponse(String token) {
    }
}
