package com.palsaekjo.yogobi.user;

import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.privacy.ConsentService;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final ConsentService consent;
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    public AuthService(JdbcTemplate jdbc, ConsentService consent) {
        this.jdbc = jdbc; this.consent = consent;
    }

    /** {@code localLogin} 은 D-34 이후 항상 false 다 — 우리가 보관하는 비밀번호가 없다. */
    public record Member(long id, String email, String name, String nickname,
                         boolean localLogin, boolean googleLogin, Long currentPlanId,
                         @com.fasterxml.jackson.annotation.JsonIgnore long credentialVersion, boolean emailVerified) {
    }

    /* ── 닉네임 자동 발급·변경 (D-22) ───────────────────────────────────────── */

    /** 닉네임에 쓸 수 있는 문자만 남긴다. 남는 게 없으면 "user". */
    private static String nicknameBase(String name, String email) {
        String source = name != null && !name.isBlank() ? name : email.split("@")[0];
        String cleaned = source.replaceAll("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}]", "");
        if (cleaned.isEmpty()) return "user";
        // 숫자 5자를 붙여도 30자를 넘지 않게 자른다(V12 컬럼 길이).
        return cleaned.substring(0, Math.min(cleaned.length(), 25));
    }

    /**
     * `이름 + 숫자 5자` 로 만든다. 겹치면 다시 뽑고, 그래도 안 되면 자릿수를 늘려 반드시 성공시킨다
     * — 가입이 닉네임 때문에 실패하면 안 된다.
     */
    private String freshNickname(String name, String email) {
        String base = nicknameBase(name, email);
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = base + String.format("%05d", random.nextInt(100_000));
            if (!nicknameTaken(candidate, 0)) return candidate;
        }
        return base + System.nanoTime() % 1_000_000_000L;   // 마지막 수단: 사실상 겹치지 않는다
    }

    /** 이미 쓰는 닉네임인가. {@code exceptUserId} 본인은 제외한다(자기 닉네임 유지는 충돌이 아니다). */
    private boolean nicknameTaken(String nickname, long exceptUserId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE lower(btrim(nickname)) = ? AND id <> ?",
                Integer.class, nickname.trim().toLowerCase(java.util.Locale.ROOT), exceptUserId) > 0;
    }

    /** 닉네임 변경. 본인 것을 그대로 두는 것은 충돌이 아니다(G-14 f). */
    @Transactional
    public Member changeNickname(long id, String nickname) {
        String trimmed = text(nickname, "nickname", 30);
        if (nicknameTaken(trimmed, id)) throw duplicateNickname();
        jdbc.update("UPDATE app_user SET nickname = ? WHERE id = ?", trimmed, id);
        return member(id);
    }

    /** 공백만 있거나 길이를 넘으면 400. 앞뒤 공백은 잘라 저장한다. */
    private static String text(String value, String field, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > max)
            throw ApiException.requiredMissing(field, "1자 이상 " + max + "자 이하로 입력해 주세요.");
        return trimmed;
    }

    static ApiException duplicateNickname() {
        return new ApiException("YGB-AUTH-DUP-NICK", 409, "이미 사용 중인 닉네임이에요. 다른 닉네임을 써 주세요.", "nickname");
    }

    /** 회원 탈퇴 — 이용 데이터는 파기하며 별도 법정 보존 사본에는 회원 FK가 없어 삭제가 전파되지 않는다. */
    @Transactional
    public void deleteAccount(long id) {
        if (jdbc.update("DELETE FROM app_user WHERE id=?", id) != 1) throw unauthorized();
    }

    public Member member(long id) {
        return jdbc.query("SELECT id,email,name,nickname,password_hash IS NOT NULL,google_sub IS NOT NULL,current_plan_id,credential_version,email_verified FROM app_user WHERE id=?",
                (rs, i) -> new Member(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), rs.getBoolean(6), rs.getObject(7, Long.class), rs.getLong(8), rs.getBoolean(9)),
                id).stream().findFirst().orElseThrow(AuthService::unauthorized);
    }

    @Transactional
    public Member googleLogin(OidcUser google, ConsentService.LoginConsent choices) {
        String email = googleEmail(google);
        var ids = jdbc.query("SELECT id FROM app_user WHERE google_sub=?", (rs, i) -> rs.getLong(1), google.getSubject());
        if (!ids.isEmpty()) {
            consent.recordLogin(ids.getFirst(), choices);
            return member(ids.getFirst());
        }
        try {
            long id = jdbc.queryForObject(
                    "INSERT INTO app_user(email,google_sub,email_verified,nickname) VALUES (?,?,TRUE,?) RETURNING id",
                    Long.class, email, google.getSubject(), freshNickname(null, email));
            consent.recordLogin(id, choices);
            return member(id);
        } catch (DuplicateKeyException e) { throw conflict(); } // Never auto-link by email.
    }

    private static String googleEmail(OidcUser google) {
        if (!Boolean.TRUE.equals(google.getEmailVerified()) || google.getSubject() == null
                || google.getSubject().isBlank() || google.getSubject().length() > 255) throw unauthorized();
        return email(google.getEmail());
    }

    static String email(String email) {
        if (email == null || email.length() > 254 || !email.strip().matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+"))
            throw ApiException.requiredMissing("email", "이메일 형식을 확인해 주세요.");
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public static ApiException unauthorized() {
        return new ApiException("YGB-AUTH-001", 401, "로그인 정보 또는 본인 확인을 다시 확인해 주세요.", null);
    }

    public static ApiException conflict() {
        return new ApiException("YGB-AUTH-409", 409, "계정 정보를 확인해 주세요. 기존 계정이 있다면 로그인 후 연결해 주세요.", null);
    }
}
