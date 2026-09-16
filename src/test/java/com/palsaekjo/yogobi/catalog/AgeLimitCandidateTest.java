package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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

/**
 * G-18. 가입할 수 없는 요금제는 후보에 넣지 않는다.
 * <p>{@code age_limit} 은 시드에 채워져 있었지만 조회가 쓰지 않아, 성인에게 키즈 요금제가 후보로 들어갔다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AgeLimitCandidateTest {
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
    @Autowired CatalogReader reader;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM plan_benefit");
        jdbc.execute("DELETE FROM mobile_plan");
        jdbc.execute("DELETE FROM carrier");
    }

    @Test
    void keepsOnlyPlansAnyoneCanSubscribeAndCountsTheRest() throws Exception {
        // 마지막 행만 age_limit 이 비어 있다 — 수집되지 않은 값이다.
        loader.loadMobilePlans(csv(HEADER
                + "SKT,누구나,5G,50000,20480,100,100,,,ALL,https://t/1,2026-09-07\n"
                + "KT,요고,5G,38000,20480,100,100,,,다이렉트,https://t/2,2026-09-07\n"
                + "SKT,청년,5G,45000,20480,100,100,,,청년 만 19~34세,https://t/3,2026-09-07\n"
                + "KT,키즈,5G,20000,20480,100,100,,,키즈 만 12세 이하,https://t/4,2026-09-07\n"
                + "LGU+,태블릿,5G,11000,20480,100,100,,,태블릿/웨어러블,https://t/5,2026-09-07\n"
                + "SKT,수집안됨,5G,47000,20480,100,100,,,,https://t/6,2026-09-07\n"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mobile_plan", Integer.class)).isEqualTo(6);

        var names = reader.findCandidatePlans(20480, null).stream().map(c -> c.plan().name()).toList();

        // a·b·c — 누구나 가입할 수 있다. '다이렉트' 는 자격이 아니라 온라인 가입 채널이라 빠지면 안 된다.
        assertThat(names).containsExactlyInAnyOrder("누구나", "요고", "수집안됨");
        // d·e·f — 자격이 없으면 가입 자체가 안 되므로 1등으로 보여줘도 사용자는 실패한다.
        assertThat(names).doesNotContain("청년", "키즈", "태블릿");
        // g — 안내 문구에 실제 숫자를 넣기 위한 건수.
        assertThat(reader.countAgeRestricted(20480, null)).isEqualTo(3);
    }

    @Test
    void countsNothingWhenEveryPlanIsOpenToAll() throws Exception {
        loader.loadMobilePlans(csv(HEADER
                + "SKT,누구나,5G,50000,20480,100,100,,,ALL,https://t/1,2026-09-07\n"));

        // h — 제외된 것이 없으면 0. 호출부가 이 값으로 안내를 생략한다(없는 선택지를 안내하면 소음이다).
        assertThat(reader.countAgeRestricted(20480, null)).isZero();
    }

    @Test
    void appliesTheSameFilterPerNetworkAndDataRequirement() throws Exception {
        loader.loadMobilePlans(csv(HEADER
                + "SKT,청년5G,5G,45000,20480,100,100,,,청년,https://t/1,2026-09-07\n"
                + "SKT,청년LTE,LTE,30000,20480,100,100,,,청년,https://t/2,2026-09-07\n"
                + "SKT,청년소용량,5G,25000,5120,100,100,,,청년,https://t/3,2026-09-07\n"));

        // 건수는 후보 조회와 같은 조건에서 세야 한다 — 다르면 "3건 뺐어요" 라고 해놓고 2건만 빠진다.
        assertThat(reader.countAgeRestricted(20480, "FIVE_G")).isEqualTo(1);
        assertThat(reader.countAgeRestricted(20480, null)).isEqualTo(2);
        assertThat(reader.countAgeRestricted(5120, null)).isEqualTo(3);
    }

    private static Resource csv(String body) {
        return new ByteArrayResource(body.getBytes(StandardCharsets.UTF_8));
    }
}
