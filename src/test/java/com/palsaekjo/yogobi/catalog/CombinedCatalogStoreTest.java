package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.palsaekjo.yogobi.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 합본 CSV CRUD 종단 검증(D-21): 파일이 원본이고 DB는 그 투영이다.
 * 각 케이스는 임시 복사본을 원본으로 삼아 실제 파일 쓰기 → DB 재적재까지 확인한다.
 */
@SpringBootTest
@Testcontainers
class CombinedCatalogStoreTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @TempDir
    static Path directory;
    static Path csv;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        csv = directory.resolve("catalog_combined.csv");
        Files.writeString(csv, new ClassPathResource("db/seed/catalog_combined.csv")
                .getContentAsString(StandardCharsets.UTF_8));
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("yogobi.catalog.combined-csv", () -> csv.toString());
    }

    @Autowired CombinedCatalogStore store;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void restoreSource() throws Exception {
        Files.writeString(csv, new ClassPathResource("db/seed/catalog_combined.csv")
                .getContentAsString(StandardCharsets.UTF_8));
        store.reload();
    }

    @Test
    void startupLoadsEveryDatasetIntoDatabase() {
        assertThat(count("mobile_plan")).isEqualTo(1706);
        assertThat(count("subscription_service")).isEqualTo(6);
        assertThat(count("subscription_tier")).isEqualTo(17);
        assertThat(count("bundle_product")).isEqualTo(7);
        // plan_benefit 에는 active 가 없다(V9) — 매 반영이 교체 방식이라 행 수를 그대로 센다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_benefit", Integer.class)).isEqualTo(46);
    }

    @Test
    void createWritesFileAndAppearsInDatabase() throws Exception {
        store.create("mobile_plan", Map.of(
                "carrier", "SKT", "plan_name", "테스트요고", "network_type", "5G",
                "base_price", "31000", "data_mb", "5120", "age_limit", "ALL",
                "source_url", "https://m.tworld.co.kr/plan", "collected_at", "2026-09-16"));

        assertThat(Files.readString(csv)).contains("SKT,테스트요고,5G,31000");
        assertThat(planPrice("테스트요고")).isEqualTo(31000);
        assertThat(count("mobile_plan")).isEqualTo(1707);
    }

    @Test
    void updateChangesOnlyGivenFieldsAndReloadsDatabase() {
        var updated = store.update("mobile_plan", "SKT|베스트 Max(T 우주)", Map.of("base_price", "77000"));

        assertThat(updated).containsEntry("base_price", "77000")
                .containsEntry("network_type", "5G");            // 보내지 않은 필드는 유지
        assertThat(planPrice("베스트 Max(T 우주)")).isEqualTo(77000);
    }

    /** 삭제는 행을 파일에서 지운다. DB는 회원 참조 보존을 위해 active=false로 내린다(하드 삭제 아님). */
    @Test
    void deleteRemovesRowFromFileAndDeactivatesInDatabase() throws Exception {
        store.delete("mobile_plan", "SKT|베스트 Max(T 우주)");

        assertThat(Files.readString(csv)).doesNotContain("베스트 Max(T 우주)");
        assertThat(jdbc.queryForObject(
                "SELECT active FROM mobile_plan m JOIN carrier c ON c.id=m.carrier_id WHERE c.name='SKT' AND m.name=?",
                Boolean.class, "베스트 Max(T 우주)")).isFalse();
    }

    @Test
    void duplicateKeyIsRejectedAndFileUnchanged() throws Exception {
        String before = Files.readString(csv);
        assertThatThrownBy(() -> store.create("mobile_plan", Map.of(
                "carrier", "SKT", "plan_name", "베스트 Max(T 우주)", "network_type", "5G",
                "base_price", "1000", "data_mb", "100",
                "source_url", "https://m.tworld.co.kr/plan", "collected_at", "2026-09-16")))
                .isInstanceOf(ApiException.class).hasMessageContaining("이미 존재");
        assertThat(Files.readString(csv)).isEqualTo(before);
    }

    @Test
    void unknownRowAndDatasetAreNotFound() {
        assertThatThrownBy(() -> store.update("mobile_plan", "SKT|없는요금제", Map.of("base_price", "1000")))
                .isInstanceOf(ApiException.class).hasMessageContaining("찾을 수 없습니다");
        assertThatThrownBy(() -> store.rows("nope"))
                .isInstanceOf(ApiException.class).hasMessageContaining("알 수 없는 데이터셋");
    }

    /** DB 반영이 실패하면(잘못된 값) 파일을 원상복구한다 — 원본과 투영이 갈라지면 안 된다. */
    @Test
    void databaseFailureRollsBackTheFile() throws Exception {
        String before = Files.readString(csv);
        assertThatThrownBy(() -> store.create("mobile_plan", Map.of(
                "carrier", "SKT", "plan_name", "깨진요금제", "network_type", "5G",
                "base_price", "공짜",                                   // BIGINT 캐스트 실패 → 트랜잭션 롤백
                "data_mb", "100", "source_url", "https://m.tworld.co.kr/plan", "collected_at", "2026-09-16")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("되돌렸습니다");
        assertThat(Files.readString(csv)).isEqualTo(before);
        assertThat(count("mobile_plan")).isEqualTo(1706);
    }

    @Test
    void unknownFieldIsRejected() {
        assertThatThrownBy(() -> store.update("mobile_plan", "SKT|베스트 Max(T 우주)", Map.of("bogus", "1")))
                .isInstanceOf(ApiException.class).hasMessageContaining("알 수 없는 필드");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE active", Integer.class);
    }

    private long planPrice(String name) {
        return jdbc.queryForObject("SELECT base_price FROM mobile_plan WHERE name = ?", Long.class, name);
    }
}
