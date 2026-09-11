package com.palsaekjo.yogobi.privacy;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집·이용 동의 조회·변경. 필수(ESSENTIAL)는 가입 시 {@code AuthService.signup} 이 기록하며 철회 불가(계약 이행 근거).
 * 선택(MARKETING)만 이 서비스로 동의/철회한다.
 */
@Service
public class ConsentService {
    private final JdbcTemplate jdbc;

    public ConsentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Consent(String item, String policyVersion, boolean agreed, Instant agreedAt, Instant withdrawnAt) {
    }

    public List<Consent> view(long userId) {
        return jdbc.query(
                "SELECT item, policy_version, agreed_at, withdrawn_at FROM user_consent WHERE user_id=? ORDER BY item",
                (rs, i) -> new Consent(rs.getString(1), rs.getString(2), rs.getTimestamp(4) == null,
                        rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()),
                userId);
    }

    /** 선택 항목(마케팅) 동의/철회. 동의는 최신 버전으로 갱신, 철회는 withdrawn_at 표시. */
    @Transactional
    public void setMarketing(long userId, boolean agree) {
        if (agree) {
            jdbc.update("""
                    INSERT INTO user_consent(user_id, item, policy_version) VALUES (?, 'MARKETING', ?)
                    ON CONFLICT (user_id, item)
                    DO UPDATE SET policy_version=EXCLUDED.policy_version, agreed_at=now(), withdrawn_at=NULL
                    """, userId, PrivacyPolicy.VERSION);
        } else {
            jdbc.update("UPDATE user_consent SET withdrawn_at=now() WHERE user_id=? AND item='MARKETING' AND withdrawn_at IS NULL",
                    userId);
        }
    }
}
