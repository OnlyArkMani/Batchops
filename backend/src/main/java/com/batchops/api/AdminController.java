package com.batchops.api;

import com.batchops.events.DeadLetterService;
import com.batchops.persistence.DeadLetter;
import com.batchops.persistence.KnownError;
import com.batchops.persistence.KnownErrorRepository;
import com.batchops.persistence.UserAccount;
import com.batchops.persistence.UserRepository;
import com.batchops.schedule.TaskRegistry;
import com.batchops.security.Access;
import com.batchops.security.DepartmentScope;
import com.batchops.security.TokenService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only operations that used to live in Django admin: user provisioning (there is no public
 * sign-up), the known-error catalogue, dead-letter replay and the task allow-list.
 */
@RestController
public class AdminController {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final DepartmentScope scope;
    private final TokenService tokens;
    private final KnownErrorRepository known;
    private final DeadLetterService deadLetters;
    private final TaskRegistry registry;

    public AdminController(UserRepository users, PasswordEncoder encoder, DepartmentScope scope, TokenService tokens,
                           KnownErrorRepository known, DeadLetterService deadLetters, TaskRegistry registry) {
        this.users = users;
        this.encoder = encoder;
        this.scope = scope;
        this.tokens = tokens;
        this.known = known;
        this.deadLetters = deadLetters;
        this.registry = registry;
    }

    // ------------------------------------------------------------------ users

    @GetMapping("/api/admin/users")
    public List<Map<String, Object>> listUsers() {
        Access.admin();
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserAccount u : users.findAll()) {
            out.add(adminUser(u));
        }
        return out;
    }

    @PostMapping("/api/admin/users")
    @Transactional
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
        Access.admin();
        String username = str(body.get("username"));
        String password = str(body.get("password"));
        if (username.isEmpty() || password.length() < 8) {
            throw ApiException.badRequest("username and a password of at least 8 characters are required");
        }
        if (users.existsByUsernameIgnoreCase(username)) {
            throw ApiException.conflict("username already exists");
        }
        UserAccount u = new UserAccount();
        u.username = username;
        u.email = str(body.get("email"));
        u.passwordHash = encoder.encode(password);
        u.role = role(body.get("role"), "user");
        u.emailVerified = Boolean.TRUE.equals(body.get("email_verified"));
        users.save(u);
        replaceDepartments(u, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(adminUser(u));
    }

    @PatchMapping("/api/admin/users/{id}")
    @Transactional
    public Map<String, Object> updateUser(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Access.admin();
        UserAccount u = users.findById(id).orElseThrow(() -> ApiException.notFound("user not found"));
        if (body.containsKey("role")) {
            u.role = role(body.get("role"), u.role);
        }
        if (body.containsKey("email")) {
            u.email = str(body.get("email"));
        }
        if (body.containsKey("email_verified")) {
            u.emailVerified = Boolean.TRUE.equals(body.get("email_verified"));
        }
        if (body.containsKey("active")) {
            u.active = !Boolean.FALSE.equals(body.get("active"));
            if (!u.active) {
                tokens.revokeAll(u.id);
            }
        }
        if (body.get("password") != null) {
            String pw = str(body.get("password"));
            if (pw.length() < 8) {
                throw ApiException.badRequest("password must be at least 8 characters");
            }
            u.passwordHash = encoder.encode(pw);
            tokens.revokeAll(u.id);
        }
        u.updatedAt = Instant.now();
        users.save(u);
        replaceDepartments(u, body);
        return adminUser(u);
    }

    private void replaceDepartments(UserAccount u, Map<String, Object> body) {
        if (body.get("departments") instanceof List<?> l) {
            List<String> names = new ArrayList<>();
            for (Object o : l) {
                names.add(String.valueOf(o));
            }
            scope.replace(u.id, names);
        }
    }

    private Map<String, Object> adminUser(UserAccount u) {
        Map<String, Object> m = Views.user(u);
        m.put("email", u.email);
        m.put("email_verified", u.emailVerified);
        m.put("active", u.active);
        m.put("departments", scope.list(u.id));
        m.put("created_at", u.createdAt);
        return m;
    }

    private static String role(Object r, String fallback) {
        String v = r == null ? fallback : String.valueOf(r).trim().toLowerCase();
        if (!List.of("user", "moderator", "admin").contains(v)) {
            throw ApiException.badRequest("role must be user, moderator or admin");
        }
        return v;
    }

    // ------------------------------------------------------------------ known errors

    @GetMapping("/api/known-errors")
    public List<Map<String, Object>> listKnown() {
        Access.current();
        List<Map<String, Object>> out = new ArrayList<>();
        known.findAll().forEach(k -> out.add(Views.knownError(k)));
        return out;
    }

    @PostMapping("/api/known-errors")
    @Transactional
    public ResponseEntity<Map<String, Object>> createKnown(@RequestBody Map<String, Object> body) {
        Access.admin();
        KnownError k = new KnownError();
        applyKnown(k, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(Views.knownError(known.save(k)));
    }

    @PatchMapping("/api/known-errors/{id}")
    @Transactional
    public Map<String, Object> updateKnown(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        Access.admin();
        KnownError k = known.findById(id).orElseThrow(() -> ApiException.notFound("Not found."));
        applyKnown(k, body);
        return Views.knownError(known.save(k));
    }

    @DeleteMapping("/api/known-errors/{id}")
    public ResponseEntity<Void> deleteKnown(@PathVariable UUID id) {
        Access.admin();
        known.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @SuppressWarnings("unchecked")
    private void applyKnown(KnownError k, Map<String, Object> body) {
        if (body.containsKey("pattern")) {
            String p = str(body.get("pattern"));
            try {
                Pattern.compile(p);
            } catch (PatternSyntaxException e) {
                throw ApiException.badRequest("pattern is not a valid regular expression: " + e.getDescription());
            }
            k.pattern = p;
        }
        if (k.pattern == null || k.pattern.isBlank()) {
            throw ApiException.badRequest("pattern is required");
        }
        if (body.containsKey("name")) {
            k.name = str(body.get("name"));
        }
        if (body.get("fix") instanceof Map<?, ?> f) {
            k.fix = new LinkedHashMap<>((Map<String, Object>) f);
        }
        if (body.get("examples") instanceof List<?> ex) {
            List<String> list = new ArrayList<>();
            ex.forEach(o -> list.add(String.valueOf(o)));
            k.examples = list;
        }
        if (body.containsKey("active")) {
            k.active = !Boolean.FALSE.equals(body.get("active"));
        }
        k.updatedAt = Instant.now();
    }

    // ------------------------------------------------------------------ dead letters & tasks

    @GetMapping("/api/admin/dead-letters")
    public List<Map<String, Object>> deadLetters() {
        Access.admin();
        List<Map<String, Object>> out = new ArrayList<>();
        for (DeadLetter d : deadLetters.pending()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id);
            m.put("event_id", d.eventId);
            m.put("upload_id", d.uploadId);
            m.put("stage", d.stage);
            m.put("error_class", d.errorClass);
            m.put("error", d.error);
            m.put("attempts", d.attempts);
            m.put("created_at", d.createdAt);
            out.add(m);
        }
        return out;
    }

    @PostMapping("/api/admin/dead-letters/{id}/replay")
    public Map<String, Object> replay(@PathVariable Long id) {
        Access.admin();
        boolean ok = deadLetters.replay(id);
        if (!ok) {
            throw ApiException.notFound("dead letter not found, already replayed, or its upload no longer exists");
        }
        return Map.of("status", "replayed", "id", id);
    }

    @PostMapping("/api/admin/dead-letters/replay-all")
    public Map<String, Object> replayAll() {
        Access.admin();
        return Map.of("status", "replayed", "count", deadLetters.replayAll());
    }

    @GetMapping("/api/admin/tasks")
    public Map<String, Object> tasks() {
        Access.admin();
        return Map.of("tasks", registry.names());
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
