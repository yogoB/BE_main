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

    /**
     * G-67. 절감 추천 알림도 <b>끌 수 있어야 한다</b>(2026-09-21, 사용자 승인).
     *
     * <p>이 항목은 로그인할 때 체크만 받고 끄는 길이 없었다. 처리방침 7조가 처리정지를 약속하는데
     * 화면에도 API 에도 길이 없으면 <b>그 약속이 빈말</b>이다. 프론트 세션이 마이페이지 동의 관리를
     * 열면서 지적했다.
     *
     * <p>발송 기능은 아직 없다 — 지금 남는 것은 동의 증적뿐이다. 그래도 끄는 길을 먼저 연다:
     * <b>보내기 시작한 다음에 만들면 그 사이에 받은 사람은 끌 수 없었다.</b>
     *
     * <p>마케팅과 <b>같은 모양</b>이어야 한다. 같은 성격의 선택 항목을 다른 모양으로 두면 화면이
     * 둘을 다르게 다루게 되고, 언젠가 한쪽만 고쳐진다.
     */
    @Test void g67_savingsAlertConsentCanBeTurnedOffAndBackOn() throws Exception {
        long id = signup("alice@example.com");
        // 로그인 시 선택하지 않았으면 행이 없다 — 기본은 미동의다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_consent WHERE user_id=? AND item='SAVINGS_ALERT'",
                Integer.class, id)).isZero();

        csrf();
        mvc.perform(post("/api/v1/me/consent/savings-alert").session(session).cookie(cookies).header("X-CSRF-TOKEN", csrf)
                .contentType("application/json").content("{\"agree\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.agreed").value(true));
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM user_consent WHERE user_id=? AND item='SAVINGS_ALERT'",
                java.sql.Timestamp.class, id)).isNull();

        csrf();
        mvc.perform(post("/api/v1/me/consent/savings-alert").session(session).cookie(cookies).header("X-CSRF-TOKEN", csrf)
                .contentType("application/json").content("{\"agree\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.agreed").value(false));
        // 철회는 행을 지우지 않고 시각을 찍는다 — 언제 동의했고 언제 철회했는지가 증적이다.
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM user_consent WHERE user_id=? AND item='SAVINGS_ALERT'",
                java.sql.Timestamp.class, id)).isNotNull();

        // 다시 켤 수 있다. 철회가 끝이 아니다.
        csrf();
        mvc.perform(post("/api/v1/me/consent/savings-alert").session(session).cookie(cookies).header("X-CSRF-TOKEN", csrf)
                .contentType("application/json").content("{\"agree\":true}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM user_consent WHERE user_id=? AND item='SAVINGS_ALERT'",
                java.sql.Timestamp.class, id)).isNull();
    }

    /**
     * G-67 b — 마케팅과 같은 방어를 받는다. CSRF·타입 중 하나라도 빠지면 거절한다.
     *
     * <p>비인증 POST 는 <b>401 이 아니라 403</b> 이다 — CSRF 필터가 인증보다 먼저 걸린다.
     * 인증 여부는 같은 경로의 GET 으로 본다(마케팅 테스트도 그렇게 나뉘어 있다).
     */
    @Test void g67b_savingsAlertConsentIsGuardedLikeMarketing() throws Exception {
        mvc.perform(get("/api/v1/me/consent")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/consent/savings-alert")
                .contentType("application/json").content("{\"agree\":true}")).andExpect(status().isForbidden());
        long id = signup("alice@example.com");
        mvc.perform(post("/api/v1/me/consent/savings-alert").cookie(cookies)
                .contentType("application/json").content("{\"agree\":true}")).andExpect(status().isForbidden());
        csrf();
        mvc.perform(post("/api/v1/me/consent/savings-alert").session(session).cookie(cookies).header("X-CSRF-TOKEN", csrf)
                .contentType("application/json").content("{\"agree\":\"yes\"}")).andExpect(status().isBadRequest());
        assertThat(id).isPositive();
    }

    /* ── G-27. 처리방침 버전이 오르면 (2026-09-17) ───────────────────────── */

    /** G-27a·d. 새 가입은 처음부터 현재 버전이고, 구버전 기록은 `current:false` 로 드러난다. */
    @Test void g27ad_구버전_동의는_현재가_아닌_것으로_보인다() throws Exception {
        long id = signup("a@example.com");
        mvc.perform(get("/api/v1/me/consent").cookie(cookies))
                .andExpect(jsonPath("$.data[0].item").value("ESSENTIAL"))
                .andExpect(jsonPath("$.data[0].current").value(true));

        // 방침이 올라가기 전에 동의한 회원을 흉내 낸다.
        jdbc.update("UPDATE user_consent SET policy_version='2026-09-15' WHERE user_id=?", id);
        mvc.perform(get("/api/v1/me/consent").cookie(cookies))
                .andExpect(jsonPath("$.data[0].current").value(false))
                .andExpect(jsonPath("$.data[0].agreed").value(true));   // 기록을 지우지는 않는다
    }

    /** G-27b. 확인하면 필수 항목이 현재 버전이 된다. 서비스를 막지 않는다 — 계약 이행 근거다. */
    @Test void g27b_확인하면_필수항목이_현재_버전이_된다() throws Exception {
        long id = signup("a@example.com");
        jdbc.update("UPDATE user_consent SET policy_version='2026-09-15' WHERE user_id=?", id);

        csrf();
        mvc.perform(post("/api/v1/me/consent/acknowledge").cookie(cookies).session(session)
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acknowledgedVersion").value(PrivacyPolicy.VERSION));

        mvc.perform(get("/api/v1/me/consent").cookie(cookies))
                .andExpect(jsonPath("$.data[0].item").value("ESSENTIAL"))
                .andExpect(jsonPath("$.data[0].policyVersion").value(PrivacyPolicy.VERSION))
                .andExpect(jsonPath("$.data[0].current").value(true));
    }

    /**
     * G-27c. 확인은 <b>마케팅 동의를 승계하지 않는다.</b> "방침을 읽었다"가 "광고를 받겠다"를 뜻하지 않는다.
     * 구버전 마케팅 동의는 `current:false` 로 남고, 다시 받아야 유효하다.
     */
    @Test void g27c_확인은_마케팅_동의를_승계하지_않는다() throws Exception {
        long id = signup("a@example.com");
        csrf();
        mvc.perform(post("/api/v1/me/consent/marketing").cookie(cookies).session(session)
                .header("X-CSRF-TOKEN", csrf).contentType("application/json").content("{\"agree\":true}"))
                .andExpect(status().isOk());
        jdbc.update("UPDATE user_consent SET policy_version='2026-09-15' WHERE user_id=?", id);

        csrf();
        mvc.perform(post("/api/v1/me/consent/acknowledge").cookie(cookies).session(session)
                .header("X-CSRF-TOKEN", csrf)).andExpect(status().isOk());

        var body = mvc.perform(get("/api/v1/me/consent").cookie(cookies)).andReturn()
                .getResponse().getContentAsString();
        var items = JSON.readTree(body).path("data");   // item 오름차순: ESSENTIAL, MARKETING
        assertThat(items.get(0).path("item").asText()).isEqualTo("ESSENTIAL");
        assertThat(items.get(0).path("current").asBoolean()).isTrue();
        assertThat(items.get(1).path("item").asText()).isEqualTo("MARKETING");
        assertThat(items.get(1).path("current").asBoolean()).isFalse();   // 승계되지 않았다

        // 다시 받으면 현재 버전이 된다.
        csrf();
        mvc.perform(post("/api/v1/me/consent/marketing").cookie(cookies).session(session)
                .header("X-CSRF-TOKEN", csrf).contentType("application/json").content("{\"agree\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/consent").cookie(cookies))
                .andExpect(jsonPath("$.data[1].current").value(true));
    }

    /** G-27e. 확인도 인증·CSRF 를 요구한다 — 남이 대신 눌러 줄 수 있으면 기록이 의미가 없다. */
    @Test void g27e_확인은_인증과_CSRF를_요구한다() throws Exception {
        mvc.perform(post("/api/v1/me/consent/acknowledge")).andExpect(status().isForbidden());
        signup("a@example.com");
        mvc.perform(post("/api/v1/me/consent/acknowledge").cookie(cookies))
                .andExpect(status().isForbidden());   // CSRF 없음
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
