package com.palsaekjo.yogobi.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.palsaekjo.yogobi.user.AuthTokens;
import jakarta.servlet.http.Cookie;
import java.util.Base64;
import java.util.List;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 개인정보 정책: 처리방침 공개, 가입 시 필수 동의 기록·마케팅 동의/철회, 탈퇴 시 개인 데이터 전부 파기. */
@SpringBootTest(properties = "JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh")
@AutoConfigureMockMvc
@Testcontainers
class PrivacyApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PASSWORD = "a long local password!";

    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    Cookie[] cookies = {};
    MockHttpSession session;
    String csrf;

    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE app_user CASCADE");
        cookies = new Cookie[0]; session = null; csrf = null;
    }

    void csrf() throws Exception {
        var req = get("/api/v1/auth/csrf");
        if (cookies.length > 0) req.cookie(cookies);
        if (session != null && !session.isInvalid()) req.session(session);
        var res = mvc.perform(req).andExpect(status().isOk()).andReturn();
        session = (MockHttpSession) res.getRequest().getSession(false);
        csrf = JSON.readTree(res.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    /** 검증 토큰을 직접 시드해 가입(메일 인프라 불필요). 발급된 인증 쿠키를 보관하고 userId 를 돌려준다. */
    long signup(String email) throws Exception {
        byte[] b = new byte[32]; new java.security.SecureRandom().nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        jdbc.update("INSERT INTO auth_email_token(token_hash,purpose,email,expires_at) VALUES (?,?,?,now()+interval '10 minutes')",
                AuthTokens.hash(token), "SIGNUP", email);
        csrf();
        MvcResult res = mvc.perform(post("/api/v1/auth/signup").session(session).header("X-CSRF-TOKEN", csrf)
                        .contentType("application/json").content(JSON.writeValueAsBytes(Map.of("token", token, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        cookies = res.getResponse().getCookies();
        if (session != null && session.isInvalid()) session = null;
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email=?", Long.class, email);
    }

    @Test void privacyPolicyIsPublic() throws Exception {
        mvc.perform(get("/api/v1/privacy-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(PrivacyPolicy.VERSION))
                .andExpect(jsonPath("$.data.items").isNotEmpty())
                .andExpect(jsonPath("$.data.dataSubjectRights").isNotEmpty());
    }

    @Test void essentialConsentRecordedAtSignupAndMarketingIsOptInThenWithdrawable() throws Exception {
        long id = signup("alice@example.com");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_consent WHERE user_id=? AND item='ESSENTIAL' AND withdrawn_at IS NULL", Integer.class, id))
                .isEqualTo(1);
        mvc.perform(get("/api/v1/me/consent").cookie(cookies)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty());
        // 마케팅은 기본 미동의(행 없음) → 동의 → 철회.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_consent WHERE user_id=? AND item='MARKETING'", Integer.class, id)).isZero();
        csrf();
        mvc.perform(post("/api/v1/me/consent/marketing").session(session).cookie(cookies).header("X-CSRF-TOKEN", csrf)
                .contentType("application/json").content("{\"agree\":true}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM user_consent WHERE user_id=? AND item='MARKETING'", java.sql.Timestamp.class, id)).isNull();
        csrf();
        mvc.perform(post("/api/v1/me/consent/marketing").session(session).cookie(cookies).header("X-CSRF-TOKEN", csrf)
                .contentType("application/json").content("{\"agree\":false}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM user_consent WHERE user_id=? AND item='MARKETING'", java.sql.Timestamp.class, id)).isNotNull();
    }

    @Test void marketingConsentRequiresCsrfAndBooleanAndAuth() throws Exception {
        // 비회원은 401, 잘못된 본문은 400(회원 상태에서).
        mvc.perform(get("/api/v1/me/consent")).andExpect(status().isUnauthorized());
        long id = signup("alice@example.com");
        mvc.perform(post("/api/v1/me/consent/marketing").cookie(cookies)
                .contentType("application/json").content("{\"agree\":true}")).andExpect(status().isForbidden()); // CSRF 누락
        csrf();
        mvc.perform(post("/api/v1/me/consent/marketing").session(session).cookie(cookies).header("X-CSRF-TOKEN", csrf)
                .contentType("application/json").content("{\"agree\":\"yes\"}")).andExpect(status().isBadRequest());
        assertThat(id).isPositive();
    }

    @Test void accountDeletionErasesAllPersonalDataAndRevokesSession() throws Exception {
        long id = signup("alice@example.com");
        jdbc.update("INSERT INTO user_subscription(user_id,tier_id,monthly_price) VALUES (?,2,13500)", id);
        jdbc.update("INSERT INTO payment_record(user_id,merchant_raw,amount,paid_at,source) VALUES (?,'NETFLIX.COM',13500,now(),'TEST')", id);
        jdbc.update("INSERT INTO detection_result(user_id,rule_code,target_ref,wasted_amount) VALUES (?,'TIER_DUPLICATE','svc:1',13500)", id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_session WHERE user_id=?", Integer.class, id)).isEqualTo(1);

        csrf();
        mvc.perform(delete("/api/v1/me").session(session).cookie(cookies).header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));

        for (String table : List.of("app_user", "user_subscription", "payment_record", "detection_result", "user_consent", "auth_session")) {
            String col = table.equals("app_user") ? "id" : "user_id";
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + col + "=?", Integer.class, id))
                    .as("%s 잔존", table).isZero();
        }
        mvc.perform(get("/api/v1/me").cookie(cookies)).andExpect(status().isUnauthorized());
    }

    @Test void accountDeletionRequiresCsrf() throws Exception {
        // CSRF 없는 삭제는 비로그인·로그인 모두 거부(계정 유지). CSRF 검사가 인증보다 먼저 막는다(auth.md).
        mvc.perform(delete("/api/v1/me")).andExpect(status().isForbidden());
        long id = signup("alice@example.com");
        mvc.perform(delete("/api/v1/me").cookie(cookies)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id=?", Integer.class, id)).isEqualTo(1);
    }
}
