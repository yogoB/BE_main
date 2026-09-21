package com.palsaekjo.yogobi.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * docs/testing.md G-14 — 닉네임 자동 발급·변경(D-22).
 *
 * <p>D-34 로 가입 경로가 Google 하나가 되면서 G-13(직접 가입)·G-15(복구 코드)는 폐기됐다.
 * 닉네임 규칙은 그대로 살아남는다 — Google 로 가입해도 닉네임은 필요하고, 사용자가 바꿀 수 있어야 한다.
 */
@SpringBootTest(properties = {"JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh",
        "AUTH_SECURE_COOKIES=true", "AUTH_SESSION_COOKIE_NAME=__Host-YGB_SESSION"})
@AutoConfigureMockMvc
@Testcontainers
class NicknameTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthTokens tokens;
    @Autowired AuthService members;

    @BeforeEach void clear() { jdbc.execute("TRUNCATE app_user, auth_rate_limit CASCADE"); }

    /** Google 이 돌려주는 신원. 실제 OIDC 왕복은 AuthSecurityTest 가 검증하므로 여기서는 값만 만든다. */
    static OidcUser googleUser(String subject, String email) {
        var idToken = OidcIdToken.withTokenValue("id-token")
                .claim("sub", subject).claim("email", email).claim("email_verified", true)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
    }

    /** CSRF 토큰과 쿠키를 유지하는 최소 브라우저. */
    class Browser {
        Cookie[] cookies = {};
        MockHttpSession session;

        Browser(long userId) { cookies = TestMembers.session(tokens, userId); }

        ResultActions post(String path, Object body) throws Exception {
            var csrfRequest = get("/api/v1/auth/csrf").cookie(cookies);
            if (session != null && !session.isInvalid()) csrfRequest.session(session);
            var result = mvc.perform(csrfRequest).andExpect(status().isOk()).andReturn();
            session = (MockHttpSession) result.getRequest().getSession(false);
            String csrf = JSON.readTree(result.getResponse().getContentAsString())
                    .path("data").path("token").asText();
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content(JSON.writeValueAsBytes(body))
                    .session(session).cookie(cookies).header("X-CSRF-TOKEN", csrf));
        }

        ResultActions me() throws Exception { return mvc.perform(get("/api/v1/me").cookie(cookies)); }
    }

    @Test void g14b_generatesNicknameFromEmailLocalPartOnGoogleSignup() {
        var member = members.googleLogin(googleUser("google-1", "seunghun@example.com"),
                new com.palsaekjo.yogobi.privacy.ConsentService.LoginConsent(false, false));
        // 이름이 없으므로 이메일 앞부분 + 숫자 5자다.
        assertThat(member.nickname()).matches("seunghun\\d{5}");
        assertThat(member.googleLogin()).isTrue();
        assertThat(member.localLogin()).isFalse();   // D-34: 우리가 보관하는 비밀번호가 없다
    }

    @Test void g14c_generatedNicknameNeverCollides() {
        var first = members.googleLogin(googleUser("google-1", "same@example.com"),
                new com.palsaekjo.yogobi.privacy.ConsentService.LoginConsent(false, false));
        // 같은 앞부분을 쓰는 다른 계정이 와도 닉네임이 겹치지 않는다.
        var second = members.googleLogin(googleUser("google-2", "same@other.example.com"),
                new com.palsaekjo.yogobi.privacy.ConsentService.LoginConsent(false, false));
        assertThat(second.nickname()).isNotEqualTo(first.nickname());
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT nickname) FROM app_user", Integer.class)).isEqualTo(2);
    }

    @Test void g14d_changesNicknameAndKeepsOwnValue() throws Exception {
        var browser = new Browser(TestMembers.create(jdbc, "a@example.com"));

        browser.post("/api/v1/me/nickname", Map.of("nickname", "새닉네임"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("새닉네임"));
        browser.me().andExpect(jsonPath("$.data.nickname").value("새닉네임"));

        // 자기 닉네임을 앞뒤 공백만 바꿔 다시 넣는 것은 충돌이 아니다(G-14 f).
        browser.post("/api/v1/me/nickname", Map.of("nickname", " 새닉네임 ")).andExpect(status().isOk());
    }

    @Test void g14e_rejectsNicknameTakenByAnother() throws Exception {
        long other = TestMembers.create(jdbc, "other@example.com");
        jdbc.update("UPDATE app_user SET nickname = '선점닉' WHERE id = ?", other);
        var browser = new Browser(TestMembers.create(jdbc, "a@example.com"));

        browser.post("/api/v1/me/nickname", Map.of("nickname", "선점닉"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("YGB-AUTH-DUP-NICK"));
    }
}
