package com.palsaekjo.yogobi.user;

import com.palsaekjo.yogobi.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AuthRateLimit limits;
    private final AuthEmail emailProofs;
    private final String dummyHash;
    /** 메일 기능이 켜져 있으면 이메일 소유 확인이 필수다. 꺼져 있으면 확인할 방법이 없어 직접 가입을 연다(D-20). */
    private final boolean emailEnabled;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwords, AuthRateLimit limits, AuthEmail emailProofs,
                       @org.springframework.beans.factory.annotation.Value("${yogobi.auth.email-enabled:false}") boolean emailEnabled) {
        this.jdbc = jdbc; this.passwords = passwords; this.limits = limits; this.emailProofs = emailProofs;
        this.emailEnabled = emailEnabled;
        dummyHash = passwords.encode(java.util.UUID.randomUUID().toString());
    }

    public record Member(long id, String email, String name, String nickname,
                         boolean localLogin, boolean googleLogin, Long currentPlanId,
                         @com.fasterxml.jackson.annotation.JsonIgnore long credentialVersion, boolean emailVerified) { }
    private record Credentials(long id, String passwordHash, long version) { }

    @Transactional
    public Member signup(String token, String password) {
        String hash = encodePassword(password);
        var proof = emailProofs.consume(token, AuthEmail.Purpose.SIGNUP);
        try {
            long id = jdbc.queryForObject("INSERT INTO app_user(email,password_hash,email_verified) VALUES (?,?,TRUE) RETURNING id",
                    Long.class, proof.email(), hash);
            jdbc.update("DELETE FROM auth_email_token WHERE email=?", proof.email());
            recordEssentialConsent(id);
            return member(id);
        } catch (DuplicateKeyException e) { throw conflict(); }
    }

    /**
     * 메일 없이 바로 가입한다(D-20). **이메일 소유는 확인하지 않는다** — email_verified 는 FALSE 로 남고,
     * 로그인 게이트는 메일 기능이 켜졌을 때만 적용된다(login). SMTP 를 켜면 토큰 흐름을 써야 한다.
     * 이메일·닉네임 중복은 서로 다른 코드로 구분해 돌려준다 — 사용자가 할 일이 다르다.
     */
    @Transactional
    public Member signupDirect(String name, String rawEmail, String password, String nickname) {
        if (emailEnabled)
            throw ApiException.requiredMissing("token",
                    "본인 확인 메일로 가입해 주세요. 메일의 링크에서 이어서 진행할 수 있어요.");
        String normalized = email(rawEmail);
        String trimmedName = text(name, "name", 50);
        String trimmedNickname = text(nickname, "nickname", 30);
        String hash = encodePassword(password);
        if (exists("lower(btrim(email)) = ?", normalized)) throw duplicateEmail();
        if (exists("lower(btrim(nickname)) = ?", trimmedNickname.toLowerCase(java.util.Locale.ROOT)))
            throw duplicateNickname();
        try {
            long id = jdbc.queryForObject(
                    "INSERT INTO app_user(email, password_hash, email_verified, name, nickname)"
                            + " VALUES (?, ?, FALSE, ?, ?) RETURNING id",
                    Long.class, normalized, hash, trimmedName, trimmedNickname);
            recordEssentialConsent(id);
            return member(id);
        } catch (DuplicateKeyException e) {
            // 동시에 같은 값이 들어온 경우. 어느 쪽이 겹쳤는지 다시 확인해 알려준다.
            throw exists("lower(btrim(email)) = ?", normalized) ? duplicateEmail() : duplicateNickname();
        }
    }

    private boolean exists(String where, Object value) {
        return jdbc.queryForObject("SELECT count(*) FROM app_user WHERE " + where, Integer.class, value) > 0;
    }

    /** 공백만 있거나 길이를 넘으면 400. 앞뒤 공백은 잘라 저장한다. */
    private static String text(String value, String field, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > max)
            throw ApiException.requiredMissing(field, "1자 이상 " + max + "자 이하로 입력해 주세요.");
        return trimmed;
    }

    static ApiException duplicateEmail() {
        return new ApiException("YGB-AUTH-DUP-EMAIL", 409, "이미 가입된 이메일이에요. 로그인하거나 다른 이메일을 써 주세요.", "email");
    }

    static ApiException duplicateNickname() {
        return new ApiException("YGB-AUTH-DUP-NICK", 409, "이미 사용 중인 닉네임이에요. 다른 닉네임을 써 주세요.", "nickname");
    }

    /** 회원 탈퇴 — 이용 데이터는 파기하며 별도 법정 보존 사본에는 회원 FK가 없어 삭제가 전파되지 않는다. */
    @Transactional
    public void deleteAccount(long id) {
        if (jdbc.update("DELETE FROM app_user WHERE id=?", id) != 1) throw unauthorized();
    }

    // 가입 시 필수 처리(계약 이행) 동의를 현재 처리방침 버전으로 기록한다. 같은 트랜잭션에서 실행.
    private void recordEssentialConsent(long id) {
        jdbc.update("INSERT INTO user_consent(user_id,item,policy_version) VALUES (?,'ESSENTIAL',?)",
                id, com.palsaekjo.yogobi.privacy.PrivacyPolicy.VERSION);
    }

    public Member login(String email, String password) {
        String normalized = email(email);
        limits.check("email:" + normalized, 10);
        var credentials = jdbc.query("SELECT id,password_hash,credential_version FROM app_user WHERE lower(btrim(email))=?",
                (rs, i) -> new Credentials(rs.getLong(1), rs.getString(2), rs.getLong(3)), normalized);
        Credentials found = credentials.isEmpty() ? new Credentials(0, null, -1) : credentials.getFirst();
        if (!matches(password, found.passwordHash())) throw unauthorized();
        var member = member(found.id());
        // 메일이 꺼져 있으면 소유를 확인할 방법 자체가 없다. 켜면 기존대로 확인을 요구한다(D-20).
        if ((emailEnabled && !member.emailVerified()) || member.credentialVersion() != found.version())
            throw unauthorized();
        return member;
    }

    public long verifyPassword(long id, String password) {
        limits.check("reauth:" + id, 10);
        var found = jdbc.queryForObject("SELECT password_hash,credential_version FROM app_user WHERE id=?",
                (rs, i) -> new Credentials(id, rs.getString(1), rs.getLong(2)), id);
        if (!matches(password, found.passwordHash())) throw unauthorized();
        return found.version();
    }

    private boolean matches(String password, String hash) {
        boolean valid = password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean matched = passwords.matches(valid ? password : "", hash == null ? dummyHash : hash);
        return valid && hash != null && matched;
    }

    public Member member(long id) {
        return jdbc.query("SELECT id,email,name,nickname,password_hash IS NOT NULL,google_sub IS NOT NULL,current_plan_id,credential_version,email_verified FROM app_user WHERE id=?",
                (rs, i) -> new Member(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), rs.getBoolean(6), rs.getObject(7, Long.class), rs.getLong(8), rs.getBoolean(9)),
                id).stream().findFirst().orElseThrow(AuthService::unauthorized);
    }

    @Transactional
    public Member googleLogin(OidcUser google) {
        String email = googleEmail(google);
        var ids = jdbc.query("SELECT id FROM app_user WHERE google_sub=?", (rs, i) -> rs.getLong(1), google.getSubject());
        if (!ids.isEmpty()) return member(ids.getFirst());
        try {
            long id = jdbc.queryForObject("INSERT INTO app_user(email,google_sub,email_verified) VALUES (?,?,TRUE) RETURNING id",
                    Long.class, email, google.getSubject());
            recordEssentialConsent(id);
            return member(id);
        } catch (DuplicateKeyException e) { throw conflict(); } // Never auto-link by email.
    }

    @Transactional
    public Member linkGoogle(long id, OidcUser google, long version) {
        String email = googleEmail(google);
        if (!member(id).email().equalsIgnoreCase(email)) throw conflict();
        try {
            if (jdbc.update("UPDATE app_user SET google_sub=?,credential_version=credential_version+1 WHERE id=? AND password_hash IS NOT NULL AND google_sub IS NULL AND credential_version=?",
                    google.getSubject(), id, version) != 1) throw conflict();
        } catch (DuplicateKeyException e) { throw conflict(); }
        return member(id);
    }

    @Transactional
    public Member addPassword(long id, String hash, OidcUser google, long version) {
        googleEmail(google);
        if (jdbc.update("UPDATE app_user SET password_hash=?,credential_version=credential_version+1 WHERE id=? AND google_sub=? AND password_hash IS NULL AND credential_version=?",
                hash, id, google.getSubject(), version) != 1) throw conflict();
        return member(id);
    }

    private static String googleEmail(OidcUser google) {
        if (!Boolean.TRUE.equals(google.getEmailVerified()) || google.getSubject() == null
                || google.getSubject().isBlank() || google.getSubject().length() > 255) throw unauthorized();
        return email(google.getEmail());
    }

    @Transactional
    public void resetPassword(String token, String password) {
        String hash = encodePassword(password);
        var proof = emailProofs.consume(token, AuthEmail.Purpose.RESET);
        if (proof.userId() == null || jdbc.update("""
                UPDATE app_user SET password_hash=?,email_verified=TRUE,credential_version=credential_version+1
                WHERE id=? AND credential_version=? AND password_hash IS NOT NULL AND lower(btrim(email))=?
                """, hash, proof.userId(), proof.credentialVersion(), proof.email()) != 1) throw AuthEmail.invalid();
        jdbc.update("DELETE FROM auth_session WHERE user_id=?", proof.userId());
        jdbc.update("DELETE FROM auth_email_token WHERE user_id=? OR email=?", proof.userId(), proof.email());
        emailProofs.notifyResetAfterCommit(proof.email());
    }

    public String encodePassword(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 15
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw ApiException.requiredMissing("password", "비밀번호는 15자 이상, UTF-8 기준 72바이트 이하로 입력해 주세요.");
        return passwords.encode(password);
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
