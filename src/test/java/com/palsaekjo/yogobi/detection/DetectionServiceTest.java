package com.palsaekjo.yogobi.detection;

import static org.assertj.core.api.Assertions.assertThat;

import com.palsaekjo.yogobi.common.DetectionRule;
import com.palsaekjo.yogobi.detection.domain.DetectionFinding;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 탐지 서비스 종단 검증(DB → 탐지 → 저장). 시드 티어/번들 + fixture 요금제·구독으로 G-09 재현. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class DetectionServiceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired DetectionService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM detection_result");
        jdbc.execute("DELETE FROM user_subscription");
        jdbc.execute("DELETE FROM app_user");
        jdbc.execute("DELETE FROM plan_benefit");
        jdbc.execute("DELETE FROM mobile_plan");
        jdbc.execute("DELETE FROM carrier");
        jdbc.execute("INSERT INTO carrier(id,name,carrier_type) VALUES (1,'SKT','MNO')");
        // 웨이브 무료 요금제
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,source_url,collected_at)
                VALUES (1,1,'웨이브팩','FIVE_G',45000,100000,999999,9999,'http://seed','2026-09-08')""");
        jdbc.execute("""
                INSERT INTO plan_benefit(mobile_plan_id,service_id,tier_id,benefit_type,is_exclusive,source_url,collected_at)
                VALUES (1,4,NULL,'FREE',false,'http://seed','2026-09-08')""");
    }

    private long newUser(Long currentPlanId) {
        return jdbc.queryForObject(
                "INSERT INTO app_user(email,password_hash,current_plan_id) VALUES (?, 'x', ?) RETURNING id",
                Long.class, "u" + System.nanoTime() + "@t.com", currentPlanId);
    }

    private void subscribe(long userId, long tierId, long price) {
        jdbc.update("INSERT INTO user_subscription(user_id,tier_id,monthly_price) VALUES (?,?,?)",
                userId, tierId, price);
    }

    @Test
    void benefitOverlap_웨이브무료요금제인데_웨이브결제() {
        long user = newUser(1L);              // current plan = 웨이브 무료
        subscribe(user, 12L, 10_900);         // 웨이브 스탠다드 결제 중

        assertSingle(service.detectForUser(user), DetectionRule.BENEFIT_OVERLAP, 10_900);
        assertThat(jdbc.queryForObject(
                "SELECT wasted_amount FROM detection_result WHERE user_id=?", Long.class, user)).isEqualTo(10_900L);
    }

    @Test
    void bundleOverlap_티빙_웨이브_개별구독() {
        long user = newUser(null);            // 요금제 미설정 → 혜택 없음
        subscribe(user, 8L, 13_500);          // 티빙 스탠다드
        subscribe(user, 12L, 10_900);         // 웨이브 스탠다드 (번들 B4 = 8,12, 15,000)

        assertSingle(service.detectForUser(user), DetectionRule.BUNDLE_OVERLAP, 9_400);
    }

    @Test
    void tierDuplicate_넷플_두티어() {
        long user = newUser(null);
        subscribe(user, 2L, 13_500);          // 넷플 스탠다드
        subscribe(user, 3L, 17_000);          // 넷플 프리미엄

        assertSingle(service.detectForUser(user), DetectionRule.TIER_DUPLICATE, 13_500);
    }

    @Test
    void reloadReplacesResults() {
        long user = newUser(1L);
        subscribe(user, 12L, 10_900);
        service.detectForUser(user);
        service.detectForUser(user);          // 재실행

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM detection_result WHERE user_id=?", Integer.class, user)).isEqualTo(1);
    }

    private static void assertSingle(List<DetectionFinding> findings, DetectionRule rule, long wasted) {
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo(rule);
            assertThat(f.wastedAmount()).isEqualTo(wasted);
        });
    }
}
