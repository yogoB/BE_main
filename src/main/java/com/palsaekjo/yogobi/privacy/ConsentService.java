package com.palsaekjo.yogobi.privacy;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집·이용 동의 조회·변경. 필수(ESSENTIAL)는 가입 시 기록하며 철회 불가(계약 이행 근거).
 * 선택(MARKETING)만 이 서비스로 동의/철회한다.
 *
 * <h2>처리방침 버전이 오르면</h2>
 * 기존 기록의 {@code policy_version} 이 뒤처진다. 두 항목의 법적 근거가 달라 처리도 다르다.
 * <ul>
 *   <li><b>ESSENTIAL — 계약 이행</b>: 동의가 아니라 고지다. 서비스를 막지 않고,
 *       사용자가 바뀐 내용을 확인하면({@link #acknowledge}) 그 시점을 새 버전으로 기록한다.
 *   <li><b>MARKETING — 동의</b>: <b>구버전 동의를 새 버전으로 자동 승계하지 않는다.</b>
 *       기록은 지우지 않고 {@code current=false} 로 드러내며, 다시 받아야 유효하다.
 * </ul>
 * 조용히 버전만 올리면 "사용자가 무엇에 동의했는가" 를 잃는다 — 그게 동의 기록의 전부다.
 */
@Service
public class ConsentService {
    private final JdbcTemplate jdbc;

    public ConsentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code current} 는 이 기록이 <b>지금 처리방침 버전</b>에 대한 것인지다. false 면 다시 받아야 한다. */
    public record Consent(String item, String policyVersion, boolean agreed, boolean current,
                          Instant agreedAt, Instant withdrawnAt) {
    }

    public List<Consent> view(long userId) {
        return jdbc.query(
                "SELECT item, policy_version, agreed_at, withdrawn_at FROM user_consent WHERE user_id=? ORDER BY item",
                (rs, i) -> new Consent(rs.getString(1), rs.getString(2), rs.getTimestamp(4) == null,
                        PrivacyPolicy.VERSION.equals(rs.getString(2)),
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

    /**
     * 바뀐 처리방침을 확인했다고 기록한다. <b>필수(ESSENTIAL) 항목만</b> 현재 버전으로 올린다.
     *
     * <p>마케팅은 건드리지 않는다 — 동의 근거이므로 "방침을 읽었다"가 "광고를 받겠다"를 뜻하지 않는다.
     * 구버전 마케팅 동의는 {@code current=false} 로 남고 {@link #setMarketing}(true) 로 다시 받아야 한다.
     *
     * <p>가입 기록이 없는 회원(있을 수 없지만)에게는 새로 만든다 — 확인했는데 기록이 없으면 안 된다.
     *
     * @return 확인 처리된 버전
     */
    @Transactional
    public String acknowledge(long userId) {
        jdbc.update("""
                INSERT INTO user_consent(user_id, item, policy_version) VALUES (?, 'ESSENTIAL', ?)
                ON CONFLICT (user_id, item)
                DO UPDATE SET policy_version = EXCLUDED.policy_version, agreed_at = now(), withdrawn_at = NULL
                """, userId, PrivacyPolicy.VERSION);
        return PrivacyPolicy.VERSION;
    }
}
