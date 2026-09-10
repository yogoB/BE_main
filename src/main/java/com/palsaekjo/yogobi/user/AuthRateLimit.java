package com.palsaekjo.yogobi.user;

import com.palsaekjo.yogobi.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthRateLimit {
    private final JdbcTemplate jdbc;

    public AuthRateLimit(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Independent transaction: rejected credentials must still count toward the limit.
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public void check(String key, int maximum) {
        jdbc.update("DELETE FROM auth_rate_limit WHERE expires_at <= now()");
        int attempts = jdbc.queryForObject("""
                INSERT INTO auth_rate_limit(bucket, expires_at, attempts) VALUES (?, now() + interval '15 minutes', 1)
                ON CONFLICT(bucket) DO UPDATE SET attempts = auth_rate_limit.attempts + 1
                RETURNING attempts
                """, Integer.class, AuthTokens.hash(key));
        if (attempts > maximum)
            throw new ApiException("YGB-AUTH-429", 429, "요청이 많습니다. 잠시 후 다시 시도해 주세요.", null);
    }
}
