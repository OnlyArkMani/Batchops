package com.batchops.security;

import com.batchops.api.ApiException;
import com.batchops.config.AppProperties;
import com.batchops.mail.MailService;
import com.batchops.persistence.CodeRequest;
import com.batchops.persistence.CodeRequestRepository;
import com.batchops.persistence.UserAccount;
import com.batchops.persistence.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Login, password reset and e-mail verification flows. */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration CODE_TTL = Duration.ofMinutes(30);

    private final UserRepository users;
    private final CodeRequestRepository codes;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final LoginThrottle throttle;
    private final MailService mail;
    private final AppProperties props;
    private final String dummyHash;

    public AuthService(UserRepository users, CodeRequestRepository codes, PasswordEncoder encoder,
                       TokenService tokens, LoginThrottle throttle, MailService mail, AppProperties props) {
        this.users = users;
        this.codes = codes;
        this.encoder = encoder;
        this.tokens = tokens;
        this.throttle = throttle;
        this.mail = mail;
        this.props = props;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    public record LoginResult(String token, UserAccount user) {
    }

    /** Thrown when credentials are right but the address has not been verified yet. */
    public static class VerificationRequired extends RuntimeException {
        public VerificationRequired() {
            super("email not verified");
        }
    }

    @Transactional
    public LoginResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw ApiException.badRequest("username and password required");
        }
        if (throttle.isLocked(username)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too many failed attempts, try again later");
        }
        Optional<UserAccount> found = users.findByUsernameIgnoreCase(username.trim()).filter(u -> u.active);
        // Always run one bcrypt comparison so response time does not reveal whether the user exists.
        boolean ok = encoder.matches(password, found.map(u -> u.passwordHash).orElse(dummyHash)) && found.isPresent();
        if (!ok) {
            throttle.failure(username);
            throw ApiException.badRequest("invalid credentials");
        }
        UserAccount user = found.get();
        if (("user".equals(user.role) || "moderator".equals(user.role)) && !user.emailVerified) {
            throw new VerificationRequired();
        }
        throttle.success(username);
        return new LoginResult(tokens.issue(user), user);
    }

    @Transactional
    public void logout(UserAccount user) {
        tokens.revokeAll(user.id);
    }

    // ------------------------------------------------------------------ forgot / reset

    @Transactional
    public Map<String, Object> forgot(String username) {
        if (username == null || username.isBlank()) {
            throw ApiException.badRequest("username required");
        }
        Optional<UserAccount> found = users.findByUsernameIgnoreCase(username.trim()).filter(u -> u.active);
        if (found.isEmpty()) {
            return decoy(); // do not reveal whether the account exists
        }
        UserAccount user = found.get();
        if (user.email == null || user.email.isBlank()) {
            throw ApiException.badRequest("email is missing for this user");
        }
        if (!user.emailVerified) {
            throw ApiException.forbidden("email not verified");
        }
        Issued issued = newCode(user, "reset");
        CodeRequest req = issued.req();
        mail.send(user.email, "BatchOps password reset code", "Your password reset code is " + issued.code() + ".\n"
                + "It expires at " + req.expiresAt + ".\n\nIf you did not request this, you can ignore this email.");
        return response("reset_id", req);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void reset(String resetId, String code, String newPassword) {
        if (isBlank(resetId) || isBlank(code) || isBlank(newPassword)) {
            throw ApiException.badRequest("reset_id, code, and new_password are required");
        }
        if (newPassword.length() < 8) {
            throw ApiException.badRequest("new_password must be at least 8 characters");
        }
        CodeRequest req = load(resetId, "reset", "reset request not found");
        checkCode(req, code, "reset");
        UserAccount user = users.findById(req.userId).orElseThrow(() -> ApiException.notFound("user not found"));
        user.passwordHash = encoder.encode(newPassword);
        user.updatedAt = Instant.now();
        users.save(user);
        req.usedAt = Instant.now();
        codes.save(req);
        tokens.revokeAll(user.id);
    }

    // ------------------------------------------------------------------ verify e-mail

    @Transactional
    public Map<String, Object> sendVerification(String username) {
        if (isBlank(username)) {
            throw ApiException.badRequest("username required");
        }
        Optional<UserAccount> found = users.findByUsernameIgnoreCase(username.trim()).filter(u -> u.active);
        if (found.isEmpty()) {
            Map<String, Object> d = decoy();
            d.put("verification_id", d.remove("reset_id"));
            return d;
        }
        UserAccount user = found.get();
        if (user.email == null || user.email.isBlank()) {
            throw ApiException.badRequest("email is missing for this user");
        }
        if (user.emailVerified) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "already_verified");
            return m;
        }
        Issued issued = newCode(user, "verify");
        CodeRequest req = issued.req();
        mail.send(user.email, "Verify your BatchOps email", "Your verification code is " + issued.code() + ".\n"
                + "It expires at " + req.expiresAt + ".\n\nIf you did not request this, you can ignore this email.");
        return response("verification_id", req);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void confirmVerification(String verificationId, String username, String code) {
        if (isBlank(verificationId) || isBlank(code) || isBlank(username)) {
            throw ApiException.badRequest("verification_id, username, and code are required");
        }
        CodeRequest req = load(verificationId, "verify", "verification not found");
        UserAccount user = users.findById(req.userId).orElseThrow(() -> ApiException.notFound("user not found"));
        if (!user.username.equalsIgnoreCase(username.trim())) {
            throw ApiException.badRequest("username does not match");
        }
        checkCode(req, code, "verification");
        user.emailVerified = true;
        users.save(user);
        req.usedAt = Instant.now();
        codes.save(req);
    }

    // ------------------------------------------------------------------ internals

    private record Issued(CodeRequest req, String code) {
    }

    private Issued newCode(UserAccount user, String purpose) {
        CodeRequest r = new CodeRequest();
        r.userId = user.id;
        r.purpose = purpose;
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        r.codeHash = hash(r.requestId, code);
        r.expiresAt = Instant.now().plus(CODE_TTL);
        return new Issued(codes.save(r), code);
    }

    private CodeRequest load(String id, String purpose, String notFound) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(notFound);
        }
        return codes.findById(uuid).filter(c -> c.purpose.equals(purpose))
                .orElseThrow(() -> ApiException.notFound(notFound));
    }

    /** Wrong guesses are counted; after the limit the request is burned so codes cannot be brute-forced. */
    private void checkCode(CodeRequest req, String code, String label) {
        if (req.usedAt != null) {
            throw ApiException.badRequest(label + " code already used");
        }
        if (req.expiresAt.isBefore(Instant.now())) {
            throw ApiException.badRequest(label + " code expired");
        }
        if (req.attempts >= props.security().codeMaxAttempts()) {
            throw ApiException.badRequest(label + " code locked after too many attempts");
        }
        if (!constantTimeEquals(req.codeHash, hash(req.requestId, code.trim()))) {
            req.attempts++;
            if (req.attempts >= props.security().codeMaxAttempts()) {
                req.usedAt = Instant.now();
            }
            codes.save(req);
            throw ApiException.badRequest("invalid " + label + " code");
        }
    }

    private static Map<String, Object> response(String idKey, CodeRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(idKey, req.requestId.toString());
        m.put("expires_at", req.expiresAt.toString());
        return m;
    }

    private static Map<String, Object> decoy() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reset_id", UUID.randomUUID().toString());
        m.put("expires_at", Instant.now().plus(CODE_TTL).toString());
        return m;
    }

    private static String hash(UUID requestId, String code) {
        return TokenService.sha256(requestId + ":" + code);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
