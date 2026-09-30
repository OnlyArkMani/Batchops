package com.batchops.security;

import com.batchops.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Per-username failure counter with a temporary lock, slowing password guessing. */
@Component
public class LoginThrottle {

    private record State(int failures, Instant lockedUntil) {
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final AppProperties props;

    public LoginThrottle(AppProperties props) {
        this.props = props;
    }

    public boolean isLocked(String username) {
        State s = states.get(key(username));
        return s != null && s.lockedUntil() != null && s.lockedUntil().isAfter(Instant.now());
    }

    public void failure(String username) {
        states.merge(key(username), new State(1, null), (old, n) -> {
            int f = old.failures() + 1;
            Instant lock = f >= props.security().loginMaxFailures()
                    ? Instant.now().plus(Duration.ofMinutes(props.security().loginLockMinutes())) : old.lockedUntil();
            return new State(f, lock);
        });
    }

    public void success(String username) {
        states.remove(key(username));
    }

    private static String key(String u) {
        return u == null ? "" : u.trim().toLowerCase();
    }
}
