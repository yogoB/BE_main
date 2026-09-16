package com.palsaekjo.yogobi.user;

import com.palsaekjo.yogobi.privacy.PrivacyPolicy;
import jakarta.servlet.http.Cookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * D-34 이후 가입 경로는 Google 하나뿐이다. "로그인한 회원"이 필요할 뿐인 테스트는 회원 행을 직접
 * 만들고 세션을 발급받는다 — HTTP 로 Google 흐름을 흉내 내면 {@code AuthSecurityTest} 가 이미
 * 실제 OIDC 스텁으로 검증하는 것을 두 번 검증하면서 테스트만 느려진다.
 *
 * <p>로그인 경로 자체(state·nonce·PKCE·서명·sub 매칭)의 검증은 여기 없다. 그것은
 * {@code AuthSecurityTest} 의 몫이며 이 헬퍼는 그 경로를 우회한다.
 */
public final class TestMembers {
    private TestMembers() {
    }

    /** Google 로 가입한 회원과 같은 상태의 행을 만든다(email_verified 는 Google 이 확인해 준 값이라 TRUE). */
    public static long create(JdbcTemplate jdbc, String email) {
        String normalized = AuthService.email(email);
        long id = jdbc.queryForObject(
                "INSERT INTO app_user(email, google_sub, email_verified, nickname) VALUES (?, ?, TRUE, ?) RETURNING id",
                Long.class, normalized, "google-" + normalized, normalized.split("@")[0]);
        jdbc.update("INSERT INTO user_consent(user_id, item, policy_version) VALUES (?, 'ESSENTIAL', ?)",
                id, PrivacyPolicy.VERSION);
        return id;
    }

    /**
     * 인증 쿠키를 발급한다. 이후 MockMvc 요청에 그대로 실으면 회원으로 인증된다.
     * 같은 회원에게 두 번 부르면 서로 다른 세션 두 개가 된다(세션 목록·회수 테스트용).
     */
    public static Cookie[] session(AuthTokens tokens, long id) {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        tokens.issue(id, 0, request, response);   // 갓 만든 행의 credential_version 은 0 이다
        return response.getCookies();
    }

    /** 회원을 만들고 바로 로그인시킨다. */
    public static Cookie[] login(JdbcTemplate jdbc, AuthTokens tokens, String email) {
        return session(tokens, create(jdbc, email));
    }
}
