package com.batchops.security;

import com.batchops.persistence.UserAccount;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts "Authorization: Token &lt;key&gt;" (what the React app sends) and "Bearer &lt;key&gt;". The SSE
 * endpoint additionally accepts ?token= because browsers cannot set headers on EventSource.
 */
public class TokenAuthFilter extends OncePerRequestFilter {

    private final TokenService tokens;

    public TokenAuthFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String raw = extract(req);
        if (raw != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            tokens.authenticate(raw).ifPresent(user -> {
                var auth = new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.effectiveRole().toUpperCase())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(req, res);
    }

    private static String extract(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h != null) {
            if (h.regionMatches(true, 0, "Token ", 0, 6)) {
                return h.substring(6).trim();
            }
            if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return h.substring(7).trim();
            }
        }
        if (req.getRequestURI().startsWith("/api/events/")) {
            return req.getParameter("token");
        }
        return null;
    }

    public static UserAccount user(Object principal) {
        return principal instanceof UserAccount u ? u : null;
    }
}
