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
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 통신 요금제 시드 로더 검증. 통신사 이름 파생, 망 매핑, source_url 필터, 무효행 롤백, 멱등성. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class MobilePlanSeedTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String HEADER = "carrier,plan_name,network_type,base_price,data_mb,voice_min,"
            + "sms_cnt,contract_discount_12m,contract_discount_24m,age_limit,source_url,collected_at\n";

    @Autowired CatalogSeedLoader loader;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM mobile_plan");
        jdbc.execute("DELETE FROM carrier");
    }

    @Test
    void loadsPlansDerivesCarriersMapsNetworkAndSkipsRowsWithoutSource() throws Exception {
        loader.loadMobilePlans(csv(HEADER
                + "SKT,5G 슬림+,5G,55000,999999,999999,999999,2400,4800,ALL,https://t/1,2026-09-07\n"
                + "KT,LTE 요고,LTE,39000,10240,200,100,,,ALL,https://t/2,2026-09-07\n"
                + "헬로모바일,알뜰5G,5G,29000,15360,100,50,,,,https://t/3,2026-09-07\n"
                + "KT,출처없음,5G,10000,1000,100,100,,,ALL,,\n"));

        // source_url 없는 행은 건너뜀 → 3건
        assertThat(count("mobile_plan")).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT name FROM mobile_plan", String.class)).doesNotContain("출처없음");

        // 통신사 파생: SKT/KT MNO, 알뜰폰 MVNO
        assertThat(count("carrier")).isEqualTo(3);
        assertThat(carrierType("SKT")).isEqualTo("MNO");
        assertThat(carrierType("헬로모바일")).isEqualTo("MVNO");

        // 망 매핑 + 선택 필드 NULL
        assertThat(jdbc.queryForObject(
                "SELECT network_type FROM mobile_plan WHERE name = '5G 슬림+'", String.class)).isEqualTo("FIVE_G");
        assertThat(jdbc.queryForObject(
                "SELECT network_type FROM mobile_plan WHERE name = 'LTE 요고'", String.class)).isEqualTo("LTE");
        assertThat(jdbc.queryForObject(
                "SELECT contract_discount_24m FROM mobile_plan WHERE name = '5G 슬림+'", Long.class)).isEqualTo(4800L);
        assertThat(jdbc.queryForObject(
                "SELECT contract_discount_12m FROM mobile_plan WHERE name = 'LTE 요고'", Long.class)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT age_limit FROM mobile_plan WHERE name = '알뜰5G'", String.class)).isNull();
    }

    @Test
    void reloadUpdatesInPlaceWithoutDuplicates() throws Exception {
        Resource first = csv(HEADER + "SKT,5G 슬림+,5G,55000,999999,999999,999999,2400,4800,ALL,https://t/1,2026-09-07\n");
        loader.loadMobilePlans(first);
        loader.loadMobilePlans(csv(HEADER
                + "SKT,5G 슬림+,5G,50000,999999,999999,999999,2400,4800,ALL,https://t/1,2026-09-07\n"));

        assertThat(count("mobile_plan")).isEqualTo(1);
        assertThat(count("carrier")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT base_price FROM mobile_plan WHERE name = '5G 슬림+'", Long.class)).isEqualTo(50000L);
    }

    @Test
    void invalidRowRollsBackEntireLoad() {
        assertThatThrownBy(() -> loader.loadMobilePlans(csv(HEADER
                + "SKT,정상,5G,55000,999999,999999,999999,,,ALL,https://t/1,2026-09-07\n"
                + "SKT,음수가격,5G,-1,999999,999999,999999,,,ALL,https://t/2,2026-09-07\n")))
                .isInstanceOf(SQLException.class);
        assertThat(count("mobile_plan")).isZero();
        assertThat(count("carrier")).isZero();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String carrierType(String name) {
        return jdbc.queryForObject("SELECT carrier_type FROM carrier WHERE name = ?", String.class, name);
    }

    private static ByteArrayResource csv(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }
}
