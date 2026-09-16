package com.palsaekjo.yogobi.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
    static final String PASSWORD = "a long local password 1";

    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.palsaekjo.yogobi.user.AuthTokens tokens;
    @Autowired PaymentRetentionService paymentRetention;

    Cookie[] cookies = {};
    MockHttpSession session;
    String csrf;

    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE app_user CASCADE");
        jdbc.execute("TRUNCATE retained_payment_record");
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

    /**
     * D-34: 가입 경로는 Google 하나뿐이다. 이 테스트가 필요한 것은 "로그인한 회원"일 뿐이므로
     * 회원과 세션을 직접 만든다 — 로그인 경로 자체는 AuthSecurityTest 가 검증한다.
     */
    long signup(String email) {
        long id = com.palsaekjo.yogobi.user.TestMembers.create(jdbc, email);
        cookies = com.palsaekjo.yogobi.user.TestMembers.session(tokens, id);
        return id;
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

    @Test void deletionErasesImportedDataButKeepsExplicitEvidenceWithoutAccountLink() throws Exception {
        long id = signup("alice@example.com");
        long paymentId = jdbc.queryForObject("INSERT INTO payment_record(user_id,merchant_raw,amount,paid_at,source) VALUES (?,'MERCHANT',1000,CURRENT_DATE,'TEST') RETURNING id", Long.class, id);
        jdbc.update("INSERT INTO payment_record(user_id,merchant_raw,amount,paid_at,source) VALUES (?,'ORDINARY IMPORT',2000,CURRENT_DATE,'TEST')", id);
        var today = java.time.LocalDate.now();
        paymentRetention.preserve(paymentId, "TEST: confirmed statutory basis", today, today.plusYears(5).plusDays(1));

        csrf();
        mvc.perform(delete("/api/v1/me").session(session).cookie(cookies).header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_record", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retained_payment_record", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT amount FROM retained_payment_record", Long.class)).isEqualTo(1000L);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name='retained_payment_record'", String.class))
                .doesNotContain("user_id", "email", "password_hash", "google_sub", "token_hash");
        mvc.perform(get("/api/v1/me").cookie(cookies)).andExpect(status().isUnauthorized());
    }

    @Test void statutoryArchiveIsNotAvailableThroughMemberApi() throws Exception {
        signup("alice@example.com");
        mvc.perform(get("/api/v1/retained-payment-records").cookie(cookies)).andExpect(status().isForbidden());
        csrf();
        mvc.perform(post("/api/v1/retained-payment-records").session(session).cookie(cookies).header("X-CSRF-TOKEN", csrf)
                .contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retained_payment_record", Integer.class)).isZero();
    }
}
