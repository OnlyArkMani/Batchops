package com.batchops.api;

import com.batchops.persistence.UserAccount;
import com.batchops.security.Access;
import com.batchops.security.AuthService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    private static String s(Map<String, Object> b, String k) {
        Object v = b == null ? null : b.get(k);
        return v == null ? null : String.valueOf(v);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Map<String, Object> body) {
        try {
            AuthService.LoginResult r = auth.login(s(body, "username"), s(body, "password"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("token", r.token());
            out.put("user", Views.user(r.user()));
            return ResponseEntity.ok(out);
        } catch (AuthService.VerificationRequired e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", "email not verified");
            out.put("verification_required", true);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(out);
        }
    }

    @PostMapping("/forgot")
    public Map<String, Object> forgot(@RequestBody(required = false) Map<String, Object> body) {
        return auth.forgot(s(body, "username"));
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestBody(required = false) Map<String, Object> body) {
        auth.reset(s(body, "reset_id"), s(body, "code"), s(body, "new_password"));
        return Map.of("status", "password_reset");
    }

    @PostMapping("/verify/send")
    public Map<String, Object> sendVerification(@RequestBody(required = false) Map<String, Object> body) {
        return auth.sendVerification(s(body, "username"));
    }

    @PostMapping("/verify/confirm")
    public Map<String, Object> confirm(@RequestBody(required = false) Map<String, Object> body) {
        auth.confirmVerification(s(body, "verification_id"), s(body, "username"), s(body, "code"));
        return Map.of("status", "email_verified");
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        UserAccount u = Access.current();
        return Map.of("user", Views.user(u));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout() {
        auth.logout(Access.current());
        return Map.of("status", "logged_out");
    }
}
