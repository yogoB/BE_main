package com.palsaekjo.yogobi.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.palsaekjo.yogobi.user.AuthTokens;
import jakarta.servlet.http.Cookie;
import java.util.Base64;
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

/** /me/* 회원 API: 본인 구독 CRUD·현재 요금제·탐지 + 객체 단위 권한(남의 데이터 접근·수정 불가). 실 인증(JWT·CSRF) 경로. */
@SpringBootTest(properties = "JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh")
@AutoConfigureMockMvc
@Testcontainers
class MeApiTest {
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

    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE app_user CASCADE");
        jdbc.execute("DELETE FROM mobile_plan");
        jdbc.execute("DELETE FROM carrier");
    }

    /** 검증 토큰 직접 시드로 가입(메일 불필요) + CSRF 처리하는 최소 브라우저. */
    class Browser {
        Cookie[] cookies = {};
        MockHttpSession session;
        String csrf;

        void csrf() throws Exception {
            var req = get("/api/v1/auth/csrf");
            if (cookies.length > 0) req.cookie(cookies);
            if (session != null && !session.isInvalid()) req.session(session);
            var res = mvc.perform(req).andExpect(status().isOk()).andReturn();
            session = (MockHttpSession) res.getRequest().getSession(false);
            csrf = JSON.readTree(res.getResponse().getContentAsString()).path("data").path("token").asText();
        }

        MvcResult send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
            csrf();
            return mvc.perform(req.session(session).cookie(cookies).header("X-CSRF-TOKEN", csrf)).andReturn();
        }
    }

    long signup(String email, Browser b) throws Exception {
        byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO auth_email_token(token_hash,purpose,email,expires_at) VALUES (?,?,?,now()+interval '10 minutes')",
                AuthTokens.hash(token), "SIGNUP", email);
        b.csrf();
        var res = mvc.perform(post("/api/v1/auth/signup").session(b.session).header("X-CSRF-TOKEN", b.csrf)
                .contentType("application/json").content(JSON.writeValueAsBytes(Map.of("token", token, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        b.cookies = res.getResponse().getCookies();
        if (b.session != null && b.session.isInvalid()) b.session = null;
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email=?", Long.class, email);
    }

    @Test void addListDeleteSubscription() throws Exception {
        Browser a = new Browser(); signup("alice@example.com", a);
        var added = a.send(post("/api/v1/me/subscriptions").contentType("application/json")
                .content("{\"tierId\":2,\"monthlyPrice\":13500}"));
        assertThat(added.getResponse().getStatus()).isEqualTo(200);
        long subId = JSON.readTree(added.getResponse().getContentAsString()).path("data").path("id").asLong();

        mvc.perform(get("/api/v1/me/subscriptions").cookie(a.cookies)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].tierId").value(2))
                .andExpect(jsonPath("$.data[0].monthlyPrice").value(13500));

        assertThat(a.send(delete("/api/v1/me/subscriptions/" + subId)).getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/v1/me/subscriptions").cookie(a.cookies)).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test void setCurrentPlanValidatesExistence() throws Exception {
        jdbc.execute("INSERT INTO carrier(id,name,carrier_type) VALUES (1,'SKT','MNO')");
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,source_url,collected_at)
                VALUES (1,1,'5G 슬림','FIVE_G',55000,100000,999999,9999,'http://seed','2026-09-14')""");
        Browser a = new Browser(); long id = signup("alice@example.com", a);
        assertThat(a.send(post("/api/v1/me/current-plan").contentType("application/json").content("{\"planId\":1}"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT current_plan_id FROM app_user WHERE id=?", Long.class, id)).isEqualTo(1L);
        // 없는 요금제 → 404
        assertThat(a.send(post("/api/v1/me/current-plan").contentType("application/json").content("{\"planId\":99999}"))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test void cannotTouchOtherMembersSubscription() throws Exception {
        Browser a = new Browser(); signup("alice@example.com", a);
        long aliceSub = JSON.readTree(a.send(post("/api/v1/me/subscriptions").contentType("application/json")
                .content("{\"tierId\":2,\"monthlyPrice\":13500}")).getResponse().getContentAsString()).path("data").path("id").asLong();

        Browser b = new Browser(); signup("bob@example.com", b);
        // bob은 alice 구독을 못 지운다 → 404
        assertThat(b.send(delete("/api/v1/me/subscriptions/" + aliceSub)).getResponse().getStatus()).isEqualTo(404);
        // bob 목록엔 alice 구독이 안 보인다
        mvc.perform(get("/api/v1/me/subscriptions").cookie(b.cookies)).andExpect(jsonPath("$.data.length()").value(0));
        // alice 구독은 그대로
        mvc.perform(get("/api/v1/me/subscriptions").cookie(a.cookies)).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test void detectionsEndpointReturnsList() throws Exception {
        Browser a = new Browser(); signup("alice@example.com", a);
        mvc.perform(get("/api/v1/me/detections").cookie(a.cookies)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test void switchTimingComparesCurrentVsTarget() throws Exception {
        jdbc.execute("INSERT INTO carrier(id,name,carrier_type) VALUES (1,'SKT','MNO')");
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,source_url,collected_at)
                VALUES (1,1,'현재요금제','FIVE_G',55000,100000,999999,9999,'http://seed','2026-09-14'),
                       (2,1,'대상요금제','FIVE_G',45000,100000,999999,9999,'http://seed','2026-09-14')""");
        Browser a = new Browser(); long id = signup("alice@example.com", a);
        jdbc.update("INSERT INTO user_subscription(user_id,tier_id,monthly_price) VALUES (?,2,13500)", id); // 티어 2 정가 13,500
        a.send(post("/api/v1/me/current-plan").contentType("application/json").content("{\"planId\":1}"));

        // 현재=55000+13500=68500, 대상=45000+13500=58500, 절감=10000, 회수=ceil(60000/10000)=6 < 10 → SWITCH_NOW
        mvc.perform(get("/api/v1/me/switch-timing").cookie(a.cookies)
                        .param("targetPlanId", "2").param("switchingCost", "60000").param("remainingContractMonths", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentMonthlyCost").value(68500))
                .andExpect(jsonPath("$.data.targetMonthlyCost").value(58500))
                .andExpect(jsonPath("$.data.monthlySavings").value(10000))
                .andExpect(jsonPath("$.data.paybackMonths").value(6))
                .andExpect(jsonPath("$.data.status").value("SWITCH_NOW"));
    }

    @Test void switchTimingRequiresCurrentPlanSet() throws Exception {
        Browser a = new Browser(); signup("alice@example.com", a);
        mvc.perform(get("/api/v1/me/switch-timing").cookie(a.cookies).param("targetPlanId", "1"))
                .andExpect(status().isBadRequest()); // 현재 요금제 미설정
    }

    @Test void guestCannotAccessMe() throws Exception {
        mvc.perform(get("/api/v1/me/subscriptions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/detections")).andExpect(status().isUnauthorized());
    }

    @Test void paymentImportRequiresMemberAndCsrfAndStoresOnlyOwnAnalysis() throws Exception {
        String payload = """
                {"approved_list":[
                  {"status":"01","merchant_name":"NETFLIX","approved_amt":13500,"currency_code":"KRW","approved_dtime":"20260914123000"},
                  {"status":"01","merchant_name":"Unknown merchant","approved_amt":2000,"currency_code":"KRW","approved_dtime":"20260914123100"},
                  {"status":"02"}]}
                """;
        mvc.perform(post("/api/v1/me/payments/import").contentType("application/json").content(payload))
                .andExpect(status().isForbidden());
        Browser guest = new Browser(); guest.csrf();
        mvc.perform(post("/api/v1/me/payments/import").session(guest.session).header("X-CSRF-TOKEN",guest.csrf)
                .contentType("application/json").content(payload)).andExpect(status().isUnauthorized());

        Browser a = new Browser(); long alice = signup("alice@example.com", a);
        Browser b = new Browser(); long bob = signup("bob@example.com", b);
        mvc.perform(post("/api/v1/me/payments/import").cookie(a.cookies)
                .contentType("application/json").content(payload)).andExpect(status().isForbidden());
        var response = a.send(post("/api/v1/me/payments/import").contentType("application/json").content(payload));
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        var data = JSON.readTree(response.getResponse().getContentAsString()).path("data");
        assertThat(data.path("imported").asInt()).isEqualTo(2);
        assertThat(data.path("recognized").asInt()).isEqualTo(1);
        assertThat(data.path("unrecognized").get(0).asText()).isEqualTo("Unknown merchant");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_record WHERE user_id=?",Integer.class,alice)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_record WHERE user_id=?",Integer.class,bob)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_record WHERE service_id IS NULL",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_subscription",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retained_payment_record",Integer.class)).isZero();

        // 전체 입력 검증이 끝나기 전에는 유효한 앞 항목도 저장하면 안 된다.
        String invalidBatch = payload.replace("\"approved_amt\":2000", "\"approved_amt\":2.5");
        assertThat(a.send(post("/api/v1/me/payments/import").contentType("application/json").content(invalidBatch))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_record WHERE user_id=?",Integer.class,alice)).isEqualTo(2);
    }
}
