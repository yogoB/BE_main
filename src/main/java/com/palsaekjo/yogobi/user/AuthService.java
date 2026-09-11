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

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwords, AuthRateLimit limits, AuthEmail emailProofs) {
        this.jdbc = jdbc; this.passwords = passwords; this.limits = limits; this.emailProofs = emailProofs;
        dummyHash = passwords.encode(java.util.UUID.randomUUID().toString());
    }

    public record Member(long id, String email, boolean localLogin, boolean googleLogin, Long currentPlanId,
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

    /** 회원 탈퇴 — 개인 데이터를 전부 파기한다(app_user 삭제가 FK ON DELETE CASCADE 로 전파, V5). */
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
        if (!member.emailVerified() || member.credentialVersion() != found.version()) throw unauthorized();
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
        return jdbc.query("SELECT id,email,password_hash IS NOT NULL,google_sub IS NOT NULL,current_plan_id,credential_version,email_verified FROM app_user WHERE id=?",
                (rs, i) -> new Member(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4),
                        rs.getObject(5, Long.class), rs.getLong(6), rs.getBoolean(7)), id).stream().findFirst().orElseThrow(AuthService::unauthorized);
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
