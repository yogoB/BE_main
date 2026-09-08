package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 제휴 혜택 시드 로더 검증. 요금제 자연키 해석, source_url 필터, 요금제별 교체(멱등), 미매칭·무효 롤백. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class PlanBenefitSeedTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String HEADER = "carrier,plan_name,service_id,tier_id,benefit_type,discount_value,"
            + "is_exclusive,exclusive_group,valid_from,valid_to,source_url,collected_at\n";

    @Autowired CatalogSeedLoader loader;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void fixtures() {
        jdbc.execute("DELETE FROM plan_benefit");
        jdbc.execute("DELETE FROM mobile_plan");
        jdbc.execute("DELETE FROM carrier");
        // 혜택이 참조할 요금제를 미리 넣는다 (mobile_plan 로더가 만드는 상태를 모방)
        jdbc.execute("INSERT INTO carrier(id,name,carrier_type) VALUES (1,'SKT','MNO')");
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,source_url,collected_at)
                VALUES (1,1,'5G 슬림+','FIVE_G',55000,100000,999999,9999,'http://seed','2026-09-08')""");
    }

    @Test
    void resolvesPlanByNaturalKeyLoadsTypesAndSkipsBlankSource() throws Exception {
        loader.loadPlanBenefits(csv(HEADER
                + "SKT,5G 슬림+,1,2,FREE,,false,,,,https://t,2026-09-07\n"       // 넷플 스탠다드 무료
                + "SKT,5G 슬림+,3,,FIXED_DISCOUNT,4000,false,,,,https://t,2026-09-07\n" // 티빙 정액할인, 티어 무관
                + "SKT,5G 슬림+,4,12,FREE,,true,OTT_PICK_ONE,,,https://t,2026-09-07\n" // 택1 무료
                + "SKT,5G 슬림+,5,,FREE,,false,,,,,\n"));                         // source_url 없음 → 건너뜀

        assertThat(count("plan_benefit")).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT benefit_type FROM plan_benefit WHERE service_id=1", String.class)).isEqualTo("FREE");
        assertThat(jdbc.queryForObject(
                "SELECT tier_id FROM plan_benefit WHERE service_id=1", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT discount_value FROM plan_benefit WHERE service_id=3", java.math.BigDecimal.class))
                .isEqualByComparingTo("4000");
        assertThat(jdbc.queryForObject(
                "SELECT tier_id FROM plan_benefit WHERE service_id=3", Long.class)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT exclusive_group FROM plan_benefit WHERE service_id=4", String.class))
                .isEqualTo("OTT_PICK_ONE");
        // service_id=5 행은 source_url 없어 미적재
        assertThat(count("plan_benefit WHERE service_id=5")).isZero();
    }

    @Test
    void reloadReplacesBenefitsForCoveredPlan() throws Exception {
        loader.loadPlanBenefits(csv(HEADER
                + "SKT,5G 슬림+,1,2,FREE,,false,,,,https://t,2026-09-07\n"
                + "SKT,5G 슬림+,3,,FIXED_DISCOUNT,4000,false,,,,https://t,2026-09-07\n"));
        // 재적재 시 해당 요금제 혜택을 교체 → 중복 없이 1건만
        loader.loadPlanBenefits(csv(HEADER
                + "SKT,5G 슬림+,1,2,FREE,,false,,,,https://t,2026-09-07\n"));

        assertThat(count("plan_benefit")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT service_id FROM plan_benefit", Long.class)).isEqualTo(1L);
    }

    @Test
    void benefitForUnknownPlanFailsEntireLoad() {
        assertThatThrownBy(() -> loader.loadPlanBenefits(csv(HEADER
                + "SKT,5G 슬림+,1,2,FREE,,false,,,,https://t,2026-09-07\n"
                + "SKT,없는요금제,1,2,FREE,,false,,,,https://t,2026-09-07\n")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("plan_benefit")).isZero();
    }

    @Test
    void inconsistentBenefitTypeAndValueFails() {
        // FREE 인데 discount_value 가 있으면 DB CHECK 위반 → 전체 롤백
        assertThatThrownBy(() -> loader.loadPlanBenefits(csv(HEADER
                + "SKT,5G 슬림+,1,2,FREE,9999,false,,,,https://t,2026-09-07\n")))
                .isInstanceOf(SQLException.class);
        assertThat(count("plan_benefit")).isZero();
    }

    private int count(String tableExpr) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tableExpr, Integer.class);
    }

    private static ByteArrayResource csv(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }
}
