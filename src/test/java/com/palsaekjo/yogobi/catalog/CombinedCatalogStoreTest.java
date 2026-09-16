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

    private static final long ACTOR = 7L;   // 감사 기록에 남을 운영자 id

    @Autowired CombinedCatalogStore store;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void restoreSource() throws Exception {
        Files.writeString(csv, new ClassPathResource("db/seed/catalog_combined.csv")
                .getContentAsString(StandardCharsets.UTF_8));
        store.reload();
        jdbc.execute("TRUNCATE catalog_audit");
    }

    /** 건수를 박아두면 시드가 늘 때마다 깨진다 — 원천 파일의 섹션 행수와 맞춘다. */
    @Test
    void startupLoadsEveryDatasetIntoDatabase() {
        assertThat(count("mobile_plan")).isEqualTo(sourceRows("mobile_plan"));
        assertThat(count("subscription_service")).isEqualTo(sourceRows("subscription_service"));
        assertThat(count("subscription_tier")).isEqualTo(sourceRows("subscription_tier"));
        assertThat(count("bundle_product")).isEqualTo(sourceRows("bundle_product"));
        // plan_benefit 에는 active 가 없다(V9) — 매 반영이 교체 방식이라 행 수를 그대로 센다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_benefit", Integer.class))
                .isEqualTo(sourceRows("plan_benefit"));
    }

    private int sourceRows(String dataset) {
        return store.read().get(dataset).rows().size();
    }

    @Test
    void createWritesFileAndAppearsInDatabase() throws Exception {
        store.create("mobile_plan", Map.of(
                "carrier", "SKT", "plan_name", "테스트요고", "network_type", "5G",
                "base_price", "31000", "data_mb", "5120", "age_limit", "ALL",
                "source_url", "https://m.tworld.co.kr/plan", "collected_at", "2026-09-16"), ACTOR);

        assertThat(Files.readString(csv)).contains("SKT,테스트요고,5G,31000");
        assertThat(planPrice("테스트요고")).isEqualTo(31000);
        assertThat(count("mobile_plan")).isEqualTo(sourceRows("mobile_plan"));
    }

    @Test
    void updateChangesOnlyGivenFieldsAndReloadsDatabase() {
        var updated = store.update("mobile_plan", "SKT|베스트 Max(T 우주)", Map.of("base_price", "77000"), ACTOR);

        assertThat(updated).containsEntry("base_price", "77000")
                .containsEntry("network_type", "5G");            // 보내지 않은 필드는 유지
        assertThat(planPrice("베스트 Max(T 우주)")).isEqualTo(77000);
    }

    /** 삭제는 행을 파일에서 지운다. DB는 회원 참조 보존을 위해 active=false로 내린다(하드 삭제 아님). */
    @Test
    void deleteRemovesRowFromFileAndDeactivatesInDatabase() throws Exception {
        store.delete("mobile_plan", "SKT|베스트 Max(T 우주)", ACTOR);

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
                "source_url", "https://m.tworld.co.kr/plan", "collected_at", "2026-09-16"), ACTOR))
                .isInstanceOf(ApiException.class).hasMessageContaining("이미 존재");
        assertThat(Files.readString(csv)).isEqualTo(before);
    }

    @Test
    void unknownRowAndDatasetAreNotFound() {
        assertThatThrownBy(() -> store.update("mobile_plan", "SKT|없는요금제", Map.of("base_price", "1000"), ACTOR))
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
                "data_mb", "100", "source_url", "https://m.tworld.co.kr/plan", "collected_at", "2026-09-16"), ACTOR))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("되돌렸습니다");
        assertThat(Files.readString(csv)).isEqualTo(before);
        assertThat(count("mobile_plan")).isEqualTo(sourceRows("mobile_plan"));
    }

    @Test
    void unknownFieldIsRejected() {
        assertThatThrownBy(() -> store.update("mobile_plan", "SKT|베스트 Max(T 우주)", Map.of("bogus", "1"), ACTOR))
                .isInstanceOf(ApiException.class).hasMessageContaining("알 수 없는 필드");
    }

    /* ---- 감사 기록(D-26): 누가 언제 무엇을 어떻게 바꿨는지 ---- */

    @Test
    void createIsAuditedWithActorAndAfterRow() {
        store.create("mobile_plan", Map.of(
                "carrier", "SKT", "plan_name", "감사요고", "network_type", "5G",
                "base_price", "31000", "data_mb", "5120",
                "source_url", "https://m.tworld.co.kr/plan", "collected_at", "2026-09-16"), ACTOR);

        var entry = lastAudit();
        assertThat(entry).containsEntry("actor_id", ACTOR)
                .containsEntry("action", "CREATE")
                .containsEntry("dataset", "mobile_plan")
                .containsEntry("row_key", "SKT|감사요고")
                .containsEntry("outcome", "APPLIED");
        assertThat(entry.get("before_row")).isNull();                       // 추가는 이전 값이 없다
        assertThat((String) entry.get("after_row")).contains("SKT,감사요고,5G,31000");
        assertThat(entry.get("created_at")).isNotNull();
    }

    /** 수정은 전·후 행을 모두 남겨야 "무엇이 어떻게 바뀌었는지"를 복원할 수 있다. */
    @Test
    void updateIsAuditedWithBeforeAndAfterRows() {
        store.update("mobile_plan", "SKT|베스트 Max(T 우주)", Map.of("base_price", "77000"), ACTOR);

        var entry = lastAudit();
        assertThat(entry).containsEntry("action", "UPDATE").containsEntry("outcome", "APPLIED")
                .containsEntry("row_key", "SKT|베스트 Max(T 우주)");
        assertThat((String) entry.get("before_row")).contains(",129000,");   // 원래 금액
        assertThat((String) entry.get("after_row")).contains(",77000,");     // 바뀐 금액
    }

    @Test
    void deleteIsAuditedWithTheRemovedRow() {
        store.delete("mobile_plan", "SKT|베스트 Max(T 우주)", ACTOR);

        var entry = lastAudit();
        assertThat(entry).containsEntry("action", "DELETE").containsEntry("outcome", "APPLIED");
        assertThat((String) entry.get("before_row")).contains("베스트 Max(T 우주)");
        assertThat(entry.get("after_row")).isNull();
    }

    /** 되돌린 실패도 남는다 — 시도 자체가 감사 대상이다. */
    @Test
    void rolledBackChangeIsAuditedAsFailed() {
        assertThatThrownBy(() -> store.create("mobile_plan", Map.of(
                "carrier", "SKT", "plan_name", "실패요고", "network_type", "5G",
                "base_price", "공짜", "data_mb", "100",
                "source_url", "https://m.tworld.co.kr/plan", "collected_at", "2026-09-16"), ACTOR))
                .isInstanceOf(IllegalStateException.class);

        var entry = lastAudit();
        assertThat(entry).containsEntry("action", "CREATE").containsEntry("outcome", "FAILED")
                .containsEntry("row_key", "SKT|실패요고").containsEntry("actor_id", ACTOR);
        assertThat((String) entry.get("detail")).isNotBlank();               // 실패 사유(예외 종류)
    }

    /** 쓰기 전에 막힌 요청(404·409)은 원본을 건드리지 않았으므로 기록하지 않는다. */
    @Test
    void rejectedBeforeWriteIsNotAudited() {
        assertThatThrownBy(() -> store.update("mobile_plan", "SKT|없는요금제", Map.of("base_price", "1"), ACTOR))
                .isInstanceOf(ApiException.class);
        assertThat(auditCount()).isZero();
    }

    private Map<String, Object> lastAudit() {
        var rows = jdbc.queryForList("SELECT * FROM catalog_audit ORDER BY id DESC LIMIT 1");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM catalog_audit", Integer.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE active", Integer.class);
    }

    private long planPrice(String name) {
        return jdbc.queryForObject("SELECT base_price FROM mobile_plan WHERE name = ?", Long.class, name);
    }
}
