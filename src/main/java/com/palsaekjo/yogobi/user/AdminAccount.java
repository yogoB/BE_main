package com.palsaekjo.yogobi.user;

import com.palsaekjo.yogobi.common.ApiException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 백오피스 관리자 계정(D-32). 설정(`ADMIN_ID`/`ADMIN_PASSWORD`)이 원본이고 DB 행은 그 투영이다.
 *
 * <p>회원 로그인은 이메일 형식을 요구하지만(`AuthService.email`) 관리자는 **아이디로 로그인**하므로
 * 별도 경로를 둔다. 대신 세션·쿠키·JWT 는 회원과 **같은 장치**를 쓴다 — 인증 체계를 두 벌 만들지 않는다.
 *
 * <p>비밀번호는 설정값을 해시해 저장하고, 설정이 바뀌면 시작 시 갱신하며 **기존 세션을 모두 무효화**한다
 * (`credential_version` 증가). 설정이 비어 있으면 관리자 계정을 만들지 않고 백오피스도 잠긴다.
 */
@Component
@Order(0)
public class AdminAccount implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminAccount.class);
    /** 관리자는 회원 목록·지표에 섞이면 안 되므로 표시 이름을 고정한다. */
    private static final String DISPLAY_NAME = "요고비 운영자";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AuthRateLimit limits;
    private final String loginId;
    private final String password;
    private final AtomicLong id = new AtomicLong(0);

    public AdminAccount(JdbcTemplate jdbc, PasswordEncoder passwords, AuthRateLimit limits,
                        @Value("${ADMIN_ID:}") String loginId, @Value("${ADMIN_PASSWORD:}") String password) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.limits = limits;
        this.loginId = loginId == null ? "" : loginId.strip();
        this.password = password == null ? "" : password;
    }

    public boolean configured() {
        return !loginId.isBlank() && !password.isBlank();
    }

    /** 관리자 회원 id. 부트스트랩 전이거나 미설정이면 0. */
    public long id() {
        return id.get();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!configured()) {
            log.info("백오피스 관리자 미설정 — ADMIN_ID·ADMIN_PASSWORD 가 없으면 관리자 페이지는 잠겨 있다");
            return;
        }
        var found = jdbc.query("SELECT id, password_hash FROM app_user WHERE email = ?",
                (rs, i) -> new long[]{rs.getLong(1), 0}, loginId);
        if (found.isEmpty()) {
            long created = jdbc.queryForObject("""
                    INSERT INTO app_user (email, password_hash, email_verified, name, nickname)
                    VALUES (?, ?, TRUE, ?, ?) RETURNING id""",
                    Long.class, loginId, passwords.encode(password), DISPLAY_NAME, DISPLAY_NAME);
            id.set(created);
            log.info("백오피스 관리자 계정 생성 (id={})", created);
            return;
        }
        long existing = found.getFirst()[0];
        id.set(existing);
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, existing);
        if (!passwords.matches(password, hash)) {
            // 설정이 원본이다. 비밀번호가 바뀌었으면 기존 로그인은 전부 끊는다.
            jdbc.update("""
                    UPDATE app_user SET password_hash = ?, credential_version = credential_version + 1
                    WHERE id = ?""", passwords.encode(password), existing);
            jdbc.update("DELETE FROM auth_session WHERE user_id = ?", existing);
            log.info("백오피스 관리자 비밀번호 갱신 — 기존 세션을 무효화했다 (id={})", existing);
        }
    }

    /**
     * 아이디·비밀번호 확인. 실패는 회원 로그인과 같은 401 이며 아이디 존재 여부를 구분해 알리지 않는다.
     * 같은 아이디에 대한 시도 횟수를 제한한다(무차별 대입 방어).
     */
    public AuthService.Member login(String rawId, String rawPassword) {
        if (!configured()) throw AuthService.unauthorized();
        String attempted = rawId == null ? "" : rawId.strip();
        limits.check("admin:" + attempted, 10);
        long adminId = id.get();
        if (adminId == 0 || !loginId.equals(attempted)) throw AuthService.unauthorized();
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, adminId);
        if (hash == null || rawPassword == null || !passwords.matches(rawPassword, hash))
            throw AuthService.unauthorized();
        return jdbc.queryForObject("""
                SELECT id, email, name, nickname, credential_version FROM app_user WHERE id = ?""",
                (rs, i) -> new AuthService.Member(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        true, false, null, rs.getLong(5), true), adminId);
    }

    /** 관리자 전용 기능에서 "설정이 없어 잠김"을 구분해 알린다. */
    public static ApiException notConfigured() {
        return new ApiException("YGB-ADMIN-001", 503,
                "관리자 계정이 설정되지 않았습니다. ADMIN_ID·ADMIN_PASSWORD 를 설정해 주세요.", null);
    }
}
