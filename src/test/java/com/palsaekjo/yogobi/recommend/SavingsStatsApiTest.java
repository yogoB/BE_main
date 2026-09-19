package com.palsaekjo.yogobi.recommend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.palsaekjo.yogobi.user.TestMembers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 랜딩 표본(D-53, G-39). 공개 경로이므로 <b>금액 외에는 아무것도 나가지 않는다</b>.
 * 계정당 최신 1건, 임계값 미만이면 빈 배열, 기준(basis)은 "지금 쓰는 요금제 대비".
 */
@SpringBootTest(properties = {
        "JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh",
        "AUTH_SECURE_COOKIES=false", "AUTH_SESSION_COOKIE_NAME=YGB_SESSION",
        "yogobi.stats.cache-seconds=0"})   // 운영은 60초 캐시. 테스트는 매번 집계해야 표본 변화를 본다
@AutoConfigureMockMvc
@Testcontainers
class SavingsStatsApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SavingsStatsController controller;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM member_savings");
        jdbc.update("DELETE FROM app_user WHERE email LIKE 'sample%@example.com'");
    }

    /** 표본 1건은 표본이 아니다 — 임계값(5) 미만이면 금액을 내보내지 않는다. */
    @Test
    void belowThresholdExposesNothing() throws Exception {
        save("sample1@example.com", 12000L);
        mvc.perform(get("/api/v1/stats/savings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.samples").isEmpty())
                .andExpect(jsonPath("$.data.sampleCount").value(1))
                .andExpect(jsonPath("$.data.basis").value("CURRENT_PLAN"));
    }

    /**
     * 임계값을 넘으면 금액만 나간다. 한 사람이 결과를 여러 번 봐도 <b>최근 1건</b>만 세고(D-59),
     * 지금 요금제를 안 알려준 조회(null — 행이 안 생긴다)와 0 이하는 표본이 아니다.
     */
    @Test
    void aboveThresholdExposesAmountsOnlyOnePerAccount() throws Exception {
        for (int i = 1; i <= 5; i++) save("sample" + i + "@example.com", 10000L * i);
        save("sample1@example.com", 99000L);                    // 같은 계정이 결과를 다시 본 경우
        save("sample6@example.com", null);                      // 지금 요금제를 안 알려준 조회
        save("sample7@example.com", -3000L);                    // 더 내는 조합

        String body = mvc.perform(get("/api/v1/stats/savings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sampleCount").value(5))          // 계정 5개 (null·음수 제외)
                .andExpect(jsonPath("$.data.samples.length()").value(5))
                // 같은 계정은 최신 값 하나로 대표된다.
                .andExpect(jsonPath("$.data.samples").value(org.hamcrest.Matchers.hasItem(99000)))
                .andExpect(jsonPath("$.data.samples").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(10000))))
                .andReturn().getResponse().getContentAsString();
        // 금액 외에는 아무것도 나가지 않는다 — 공개 화면이다.
        // (계정 id 같은 짧은 정수는 금액 문자열에 우연히 들어 있으므로 필드 이름으로 본다.)
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("userId").doesNotContain("user_id").doesNotContain("@example.com")
                .doesNotContain("planName").doesNotContain("carrier").doesNotContain("seenAt");
    }

    /** G-43 — 임계값을 넘으면 1인당 평균·중앙값이 함께 나간다(D-57, 랜딩용). 미만이면 둘 다 null 이다. */
    @Test
    void perPersonAverageAppearsOnlyAboveTheThreshold() throws Exception {
        save("sample1@example.com", 10000L);
        mvc.perform(get("/api/v1/stats/savings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monthlyAverage").doesNotExist())
                .andExpect(jsonPath("$.data.monthlyMedian").doesNotExist());

        for (int i = 2; i <= 5; i++) save("sample" + i + "@example.com", 10000L * i);   // 10,20,30,40,50천
        mvc.perform(get("/api/v1/stats/savings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sampleCount").value(5))
                .andExpect(jsonPath("$.data.monthlyAverage").value(30000))   // (10+20+30+40+50)/5
                .andExpect(jsonPath("$.data.monthlyMedian").value(30000));
    }

    /** {@code savingsVsCurrent} 가 null 이면 기록 자체가 없다 — 지금 요금제를 모르면 행을 만들지 않는다. */
    private void save(String email, Long savingsVsCurrent) {
        Long userId = jdbc.query("SELECT id FROM app_user WHERE email = ?", (rs, i) -> rs.getLong(1), email)
                .stream().findFirst().orElse(null);
        if (userId == null) userId = TestMembers.create(jdbc, email);
        if (savingsVsCurrent == null) return;
        jdbc.update("""
                INSERT INTO member_savings(user_id, monthly_savings) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET monthly_savings = EXCLUDED.monthly_savings, seen_at = now()
                """, userId, savingsVsCurrent);
    }
}
