package com.palsaekjo.yogobi.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * G-19. 스마트초이스가 아는 요금제 중 우리에게 없는 것을 결손으로 남긴다(D-31).
 * <p>스냅샷은 카탈로그가 되지 않는다 — 여기서 만드는 것은 사람이 CSV 에 반영할 <b>수집 우선순위</b>다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class SmartChoiceMissingPlanTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    /** 정해진 추천 목록만 돌려준다. 실제 HTTP 는 타지 않는다. */
    private static class StubClient extends SmartChoiceClient {
        private final List<SmartChoiceRecommendation> found;

        StubClient(List<SmartChoiceRecommendation> found) {
            super(RestClient.builder(), "key", "http://localhost:1");
            this.found = found;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public List<SmartChoiceRecommendation> recommend(int d, int v, int s, int age, int type, int dis) {
            return found;
        }
    }

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM catalog_candidate");
        jdbc.execute("DELETE FROM smartchoice_plan_snapshot");
        jdbc.execute("DELETE FROM plan_benefit");
        jdbc.execute("DELETE FROM mobile_plan");
        jdbc.execute("DELETE FROM carrier");
        // 카탈로그에는 원본만 있다. 변형(Y덤)은 없다 — 실측에서 Y덤 11종이 이렇게 빠져 있었다.
        jdbc.update("INSERT INTO carrier (id, name, carrier_type) VALUES (1, 'KT', 'MNO')");
        jdbc.update("""
                INSERT INTO mobile_plan (carrier_id, name, network_type, base_price, data_mb, voice_min, sms_cnt,
                                         age_limit, source_url, collected_at)
                VALUES (1, '베이직21GB', 'FIVE_G', 58000, 21504, 100, 100, 'ALL', 'https://t/1', DATE '2026-09-07')
                """);
    }

    private SmartChoiceSweepService sweepReturning(SmartChoiceRecommendation... found) {
        return new SmartChoiceSweepService(new StubClient(List.of(found)), jdbc, 60);
    }

    @Test
    void recordsOnlyThePlansMissingFromTheCatalog() {
        sweepReturning(
                new SmartChoiceRecommendation(1, "KT", "베이직21GB", 58000, 0, "21GB"),
                new SmartChoiceRecommendation(2, "KT", "베이직21GB Y덤", 58000, 0, "42GB"),
                new SmartChoiceRecommendation(3, "kt", " 베이직 21GB ", 58000, 0, "21GB")).sweep();

        var recorded = jdbc.queryForList(
                "SELECT query_text FROM catalog_candidate WHERE kind = 'MOBILE_PLAN' ORDER BY query_text",
                String.class);
        // a — 없는 것만 남는다. b·c — 이미 있는 것은, 표기가 달라도, 남기지 않는다.
        assertThat(recorded).containsExactly("KT 베이직21GB Y덤");
    }

    @Test
    void repeatedSweepsDoNotInflateTheRequestCount() {
        var missing = new SmartChoiceRecommendation(1, "KT", "베이직21GB Y덤", 58000, 0, "42GB");
        sweepReturning(missing).sweep();
        sweepReturning(missing).sweep();
        sweepReturning(missing).sweep();

        // d — requested_cnt 는 "사용자가 몇 번 찾았나"다. 배치가 올리면 아무도 찾지 않은 것이 우선순위 1위가 된다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_candidate", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT requested_cnt FROM catalog_candidate WHERE query_text = 'KT 베이직21GB Y덤'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void keepsTheSnapshotEvenWhenNothingIsMissing() {
        sweepReturning(new SmartChoiceRecommendation(1, "KT", "베이직21GB", 58000, 0, "21GB")).sweep();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_candidate", Integer.class)).isZero();
        // f 의 이면 — 결손이 없어도 스냅샷(교차검증용)은 그대로 쌓인다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM smartchoice_plan_snapshot", Integer.class)).isEqualTo(1);
    }
}
