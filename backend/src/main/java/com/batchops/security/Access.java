package com.batchops.security;

import com.batchops.api.ApiException;
import com.batchops.persistence.UserAccount;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Role checks mirroring the original DRF permission classes. */
public final class Access {

    private Access() {
    }

    public static UserAccount current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        UserAccount u = a == null ? null : TokenAuthFilter.user(a.getPrincipal());
        if (u == null) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "authentication required");
        }
        return u;
    }

    public static UserAccount admin() {
        UserAccount u = current();
        if (!u.isAdmin()) {
            throw ApiException.forbidden("admin role required");
        }
        return u;
    }

    public static UserAccount moderator() {
        UserAccount u = current();
        if (!u.isModerator()) {
            throw ApiException.forbidden("moderator role required");
        }
        return u;
    }
}
