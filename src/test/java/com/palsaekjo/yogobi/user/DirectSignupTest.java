package com.palsaekjo.yogobi.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * docs/testing.md G-13 — 메일 없이 가입(D-20). **메일 기능이 꺼진 환경**이다.
 * 메일이 켜진 경우(f·g)는 {@link AuthSecurityTest} 가 같은 규칙의 반대편을 검증한다.
 */
@SpringBootTest(properties = {"JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh",
        "yogobi.auth.email-enabled=false", "AUTH_SECURE_COOKIES=true",
        "AUTH_SESSION_COOKIE_NAME=__Host-YGB_SESSION"})
@AutoConfigureMockMvc
@Testcontainers
class DirectSignupTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PASSWORD = "a long local password 1";

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clear() { jdbc.execute("TRUNCATE app_user, auth_rate_limit CASCADE"); }

    /** CSRF 토큰과 세션을 유지하는 최소 브라우저. AuthSecurityTest 의 idiom 과 같다. */
    class Browser {
        Cookie[] cookies = {};
        MockHttpSession session;

        ResultActions post(String path, Object body) throws Exception {
            var csrfRequest = get("/api/v1/auth/csrf");
            if (cookies.length > 0) csrfRequest.cookie(cookies);
            if (session != null && !session.isInvalid()) csrfRequest.session(session);
            var result = mvc.perform(csrfRequest).andExpect(status().isOk()).andReturn();
            session = (MockHttpSession) result.getRequest().getSession(false);
            String csrf = JSON.readTree(result.getResponse().getContentAsString())
                    .path("data").path("token").asText();
            var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .contentType("application/json").content(JSON.writeValueAsBytes(body))
                    .session(session).header("X-CSRF-TOKEN", csrf);
            if (cookies.length > 0) request.cookie(cookies);
            return mvc.perform(request);
        }

        void accept(MvcResult result) {
            Cookie[] issued = result.getResponse().getCookies();
            if (issued.length > 0) cookies = issued;
        }

        ResultActions me() throws Exception { return mvc.perform(get("/api/v1/me").cookie(cookies)); }
    }

    static Map<String, String> signup(String name, String email, String nickname) {
        return Map.of("name", name, "email", email, "password", PASSWORD, "nickname", nickname);
    }

    @Test void g13a_signsUpWithoutMailAndStaysUnverified() throws Exception {
        var browser = new Browser();
        var result = browser.post("/api/v1/auth/signup", signup("이승훈", "a@example.com", "훈이"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("a@example.com"))
                .andExpect(jsonPath("$.data.name").value("이승훈"))
                .andExpect(jsonPath("$.data.nickname").value("훈이"))
                .andReturn();
        browser.accept(result);

        // 이메일 소유를 확인한 적이 없다 — 그 사실을 DB 에 그대로 남긴다.
        assertThat(jdbc.queryForObject("SELECT email_verified FROM app_user WHERE email='a@example.com'",
                Boolean.class)).isFalse();
        // 가입과 동시에 인증 쿠키가 나와 바로 로그인 상태다.
        browser.me().andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("훈이"));
    }

    @Test void g13b_duplicateEmailIsRejectedWithItsOwnCode() throws Exception {
        new Browser().post("/api/v1/auth/signup", signup("이승훈", "a@example.com", "훈이"))
                .andExpect(status().isOk());
        new Browser().post("/api/v1/auth/signup", signup("다른사람", "A@Example.com", "다른닉"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("YGB-AUTH-DUP-EMAIL"))
                .andExpect(jsonPath("$.error.field").value("email"));
    }

    @Test void g13c_duplicateNicknameIsRejectedWithItsOwnCode() throws Exception {
        new Browser().post("/api/v1/auth/signup", signup("이승훈", "a@example.com", "훈이"))
                .andExpect(status().isOk());
        // 대소문자·공백만 다른 닉네임도 같은 닉네임으로 본다.
        new Browser().post("/api/v1/auth/signup", signup("다른사람", "b@example.com", " 훈이 "))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("YGB-AUTH-DUP-NICK"))
                .andExpect(jsonPath("$.error.field").value("nickname"));
    }

    @Test void g13d_unverifiedAccountCanLogInWhileMailIsOff() throws Exception {
        new Browser().post("/api/v1/auth/signup", signup("이승훈", "a@example.com", "훈이"))
                .andExpect(status().isOk());
        var browser = new Browser();
        var result = browser.post("/api/v1/auth/login", Map.of("email", "a@example.com", "password", PASSWORD))
                .andExpect(status().isOk()).andReturn();
        browser.accept(result);
        browser.me().andExpect(status().isOk());
    }

    // ── G-14 닉네임 자동 발급·변경 ──────────────────────────────────────────

    @Test void g14a_generatesNicknameFromNameWhenOmitted() throws Exception {
        var browser = new Browser();
        var result = browser.post("/api/v1/auth/signup",
                        Map.of("name", "이승훈", "email", "a@example.com", "password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value(org.hamcrest.Matchers.matchesPattern("이승훈\\d{5}")))
                .andReturn();
        browser.accept(result);
    }

    @Test void g14d_changesNicknameAndKeepsOwnValue() throws Exception {
        var browser = new Browser();
        browser.accept(browser.post("/api/v1/auth/signup", signup("이승훈", "a@example.com", "훈이"))
                .andExpect(status().isOk()).andReturn());

        browser.post("/api/v1/me/nickname", Map.of("nickname", "새닉네임"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("새닉네임"));
        browser.me().andExpect(jsonPath("$.data.nickname").value("새닉네임"));

        // 자기 닉네임을 대소문자만 바꿔 다시 넣는 것은 충돌이 아니다(G-14 f).
        browser.post("/api/v1/me/nickname", Map.of("nickname", " 새닉네임 "))
                .andExpect(status().isOk());
    }

    @Test void g14e_rejectsNicknameTakenByAnother() throws Exception {
        new Browser().post("/api/v1/auth/signup", signup("남", "other@example.com", "선점닉"))
                .andExpect(status().isOk());
        var browser = new Browser();
        browser.accept(browser.post("/api/v1/auth/signup", signup("이승훈", "a@example.com", "내닉"))
                .andExpect(status().isOk()).andReturn());

        browser.post("/api/v1/me/nickname", Map.of("nickname", "선점닉"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("YGB-AUTH-DUP-NICK"));
    }

    // ── G-15 복구 코드 ──────────────────────────────────────────────────────

    @Test void g15a_issuesRecoveryCodeOnceAndStoresOnlyHash() throws Exception {
        var result = new Browser().post("/api/v1/auth/signup", signup("이승훈", "a@example.com", "훈이"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recoveryCode").isNotEmpty())
                .andReturn();
        String code = JSON.readTree(result.getResponse().getContentAsString())
                .path("data").path("recoveryCode").asText();
        // 평문은 DB 에 없다 — 해시만 저장한다.
        String stored = jdbc.queryForObject(
                "SELECT recovery_code_hash FROM app_user WHERE email='a@example.com'", String.class);
        assertThat(stored).isNotNull().isNotEqualTo(code);
        // 조회 경로는 복구 코드를 돌려주지 않는다.
        var browser = new Browser();
        browser.accept(browser.post("/api/v1/auth/login", Map.of("email", "a@example.com", "password", PASSWORD))
                .andReturn());
        browser.me().andExpect(jsonPath("$.data.recoveryCode").doesNotExist());
    }

    @Test void g15bcef_recoversPasswordOnceAndRotatesCode() throws Exception {
        var signupResult = new Browser().post("/api/v1/auth/signup", signup("이승훈", "a@example.com", "훈이"))
                .andExpect(status().isOk()).andReturn();
        String code = JSON.readTree(signupResult.getResponse().getContentAsString())
                .path("data").path("recoveryCode").asText();
        String newPassword = "brand new password 2026!";

        var recovered = new Browser().post("/api/v1/auth/password/recover",
                        Map.of("email", "a@example.com", "recoveryCode", code, "newPassword", newPassword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recoveryCode").isNotEmpty())    // 새 코드가 나온다(b)
                .andReturn();
        assertThat(JSON.readTree(recovered.getResponse().getContentAsString())
                .path("data").path("recoveryCode").asText()).isNotEqualTo(code);

        // 같은 코드는 다시 쓸 수 없다(c)
        new Browser().post("/api/v1/auth/password/recover",
                        Map.of("email", "a@example.com", "recoveryCode", code, "newPassword", newPassword))
                .andExpect(status().isUnauthorized());
        // 새 비밀번호로는 로그인되고(e), 옛 비밀번호로는 안 된다(f)
        new Browser().post("/api/v1/auth/login", Map.of("email", "a@example.com", "password", newPassword))
                .andExpect(status().isOk());
        new Browser().post("/api/v1/auth/login", Map.of("email", "a@example.com", "password", PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test void g15d_wrongCodeAndUnknownEmailLookTheSame() throws Exception {
        new Browser().post("/api/v1/auth/signup", signup("이승훈", "a@example.com", "훈이"))
                .andExpect(status().isOk());
        new Browser().post("/api/v1/auth/password/recover",
                        Map.of("email", "a@example.com", "recoveryCode", "WRON-GCOD-EWRO-NGCO",
                                "newPassword", "brand new password 2026!"))
                .andExpect(status().isUnauthorized());
        new Browser().post("/api/v1/auth/password/recover",
                        Map.of("email", "nobody@example.com", "recoveryCode", "WRON-GCOD-EWRO-NGCO",
                                "newPassword", "brand new password 2026!"))
                .andExpect(status().isUnauthorized());
    }

    /** G-13 e·e2·e3: 문자와 숫자를 섞어 8자 이상(사용자 결정 2026-09-16). 한쪽만 있으면 거절한다. */
    @Test void g13e_shortPasswordAndBlankFieldsAreRejected() throws Exception {
        for (String weak : new String[]{"abcd123", "abcdefgh", "12345678"}) {   // 7자 · 숫자 없음 · 문자 없음
            new Browser().post("/api/v1/auth/signup",
                            Map.of("name", "이승훈", "email", "a@example.com", "password", weak, "nickname", "훈이"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.field").value("password"));
        }
        new Browser().post("/api/v1/auth/signup",
                        Map.of("name", "이승훈", "email", "a@example.com", "password", "abcd1234", "nickname", "훈이"))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM app_user WHERE email = 'a@example.com'");
        new Browser().post("/api/v1/auth/signup", signup("   ", "b@example.com", "훈이"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("name"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isZero();
    }
}
