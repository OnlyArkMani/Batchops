package com.batchops.security;

import com.batchops.persistence.UserAccount;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Optional per-user department restriction. A non-admin with rows in user_departments only sees
 * those departments; users without rows keep the original "sees everything" behaviour.
 */
@Component
public class DepartmentScope {

    private final JdbcTemplate jdbc;

    public DepartmentScope(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return allowed departments, or empty set meaning "no restriction". */
    public Set<String> restrictionFor(UserAccount user) {
        if (user == null || user.isAdmin()) {
            return Collections.emptySet();
        }
        List<String> rows = jdbc.queryForList("select department from user_departments where user_id = ?",
                String.class, user.id);
        return new HashSet<>(rows);
    }

    public boolean canSee(UserAccount user, String department) {
        Set<String> r = restrictionFor(user);
        return r.isEmpty() || r.contains(department);
    }

    public void replace(Long userId, List<String> departments) {
        jdbc.update("delete from user_departments where user_id = ?", userId);
        for (String d : departments) {
            if (d != null && !d.isBlank()) {
                jdbc.update("insert into user_departments(user_id, department) values (?, ?) on conflict do nothing",
                        userId, d.trim());
            }
        }
    }

    public List<String> list(Long userId) {
        return jdbc.queryForList("select department from user_departments where user_id = ? order by department",
                String.class, userId);
    }
}
