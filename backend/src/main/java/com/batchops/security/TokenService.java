package com.batchops.security;

import com.batchops.config.AppProperties;
import com.batchops.persistence.AuthToken;
import com.batchops.persistence.AuthTokenRepository;
import com.batchops.persistence.UserAccount;
import com.batchops.persistence.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Opaque bearer tokens. Only the SHA-256 of a token is stored, and tokens expire. */
@Service
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthTokenRepository tokens;
    private final UserRepository users;
    private final AppProperties props;

    public TokenService(AuthTokenRepository tokens, UserRepository users, AppProperties props) {
        this.tokens = tokens;
        this.users = users;
        this.props = props;
    }

    public String issue(UserAccount user) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        AuthToken t = new AuthToken();
        t.tokenHash = sha256(token);
        t.userId = user.id;
        t.expiresAt = Instant.now().plus(Duration.ofHours(props.security().tokenTtlHours()));
        tokens.save(t);
        return token;
    }

    public Optional<UserAccount> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Optional<AuthToken> t = tokens.findById(sha256(rawToken.trim()));
        if (t.isEmpty() || t.get().expiresAt.isBefore(Instant.now())) {
            return Optional.empty();
        }
        return users.findById(t.get().userId).filter(u -> u.active);
    }

    public void revokeAll(Long userId) {
        tokens.deleteAllForUser(userId);
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 120_000L)
    public void purgeExpired() {
        tokens.deleteExpired(Instant.now());
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
