package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class CatalogSeedLoaderTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired CatalogSeedLoader loader;
    @Autowired JdbcTemplate jdbc;

    @Test
    void restoresSnapshotReloadsWithoutDuplicatesAndRollsBackInvalidCsv() throws Exception {
        // 개수를 박아두면 마이그레이션을 더할 때마다 깨진다. 실제 파일 수와 맞춘다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(migrationFileCount());
        assertSnapshot();
        loader.run(null);
        assertSnapshot();
        assertThat(jdbc.queryForList("SELECT tier_id FROM bundle_item WHERE bundle_id = 4 ORDER BY tier_id", Long.class))
                .containsExactly(8L, 12L);
        assertThat(jdbc.queryForObject("SELECT concurrent_streams FROM subscription_tier WHERE id = 16", Integer.class))
                .isNull();
        // 시퀀스는 최대 id 다음 값이다. 건수를 박지 않고 시드 행수에서 유도한다(id 는 1..N 연속).
        long tierCount = seedRowCount("subscription_tier");
        assertThat(jdbc.queryForObject("SELECT nextval('subscription_tier_id_seq')", Long.class))
                .isEqualTo(tierCount + 1);

        var parts = CombinedCatalogCsv.bundled();
        var services = parts.get("subscription_service");
        var tiers = parts.get("subscription_tier");
        var bundles = parts.get("bundle_product");
        var changedServices = csv(services.getContentAsString(StandardCharsets.UTF_8)
                .replace("넷플릭스", "변경된 넷플릭스"));
        var invalidBundles = csv(bundles.getContentAsString(StandardCharsets.UTF_8)
                .replace("\"8,12\"", "\"8,99999\""));
        assertThatThrownBy(() -> loader.load(changedServices, tiers, invalidBundles))
                .isInstanceOf(SQLException.class);
        assertThat(jdbc.queryForObject("SELECT name FROM subscription_service WHERE id = 1", String.class))
                .isEqualTo("넷플릭스");
        assertSnapshot();

        var invalidTiers = csv(tiers.getContentAsString(StandardCharsets.UTF_8).replace(",13500,", ",-1,"));
        assertThatThrownBy(() -> loader.load(services, invalidTiers, bundles)).isInstanceOf(SQLException.class);
        var wrongHeader = csv(services.getContentAsString(StandardCharsets.UTF_8).replace("id,name", "name,id"));
        assertThatThrownBy(() -> loader.load(wrongHeader, tiers, bundles)).isInstanceOf(SQLException.class);
        var emptyBundle = csv(bundles.getContentAsString(StandardCharsets.UTF_8).replace("\"8,12\"", "\"\""));
        assertThatThrownBy(() -> loader.load(services, tiers, emptyBundle)).isInstanceOf(SQLException.class);
        assertSnapshot();

        var changedBundles = csv(bundles.getContentAsString(StandardCharsets.UTF_8).replace("\"8,12\"", "\"8,4\""));
        loader.load(changedServices, tiers, changedBundles);
        assertThat(jdbc.queryForObject("SELECT name FROM subscription_service WHERE id = 1", String.class))
                .isEqualTo("변경된 넷플릭스");
        assertThat(jdbc.queryForList("SELECT tier_id FROM bundle_item WHERE bundle_id = 4 ORDER BY tier_id", Long.class))
                .containsExactly(4L, 8L);
        loader.run(null);
        assertSnapshot();
        assertThat(jdbc.queryForObject("SELECT nextval('subscription_tier_id_seq')", Long.class))
                .isEqualTo(tierCount + 2);   // 위에서 한 번 당겼으므로 그다음 값
    }

    private void assertSnapshot() throws IOException {
        // 건수를 박아두면 시드가 늘 때마다 깨진다(2026-09-16 서비스 6→22·등급 17→89). 파일 행수와 맞춘다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_service", Integer.class))
                .isEqualTo(seedRowCount("subscription_service"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_tier", Integer.class))
                .isEqualTo(seedRowCount("subscription_tier"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bundle_product", Integer.class))
                .isEqualTo(seedRowCount("bundle_product"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bundle_item", Integer.class))
                .isEqualTo(seedBundleItemCount());
        assertThat(jdbc.queryForObject("SELECT price FROM subscription_tier WHERE id = 2", Long.class)).isEqualTo(13500L);
        // 실제 요금제 CSV(D4)가 들어왔다. 건수를 박아두면 CSV 갱신마다 깨지므로 파일 행수와 맞춘다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mobile_plan", Integer.class))
                .isEqualTo(seedRowCount("mobile_plan"));
    }

    /** db/migration 의 V*.sql 개수. Flyway 가 적용한 수와 같아야 한다. */
    private static int migrationFileCount() throws IOException {
        var directory = new ClassPathResource("db/migration").getFile();
        var files = directory.list((dir, name) -> name.startsWith("V") && name.endsWith(".sql"));
        return files == null ? 0 : files.length;
    }

    /** 시드 CSV 의 데이터 행수(헤더 제외). 없으면 0. */
    /** 내장 합본의 데이터셋 행수(헤더 제외). 카탈로그 원본은 합본 한 파일이다. */
    private static int seedRowCount(String dataset) throws IOException {
        var resource = CombinedCatalogCsv.bundled().get(dataset);
        try (var lines = resource.getContentAsString(StandardCharsets.UTF_8).lines()) {
            return (int) lines.filter(line -> !line.isBlank()).count() - 1;
        }
    }

    /**
     * 번들 구성품 수 = {@code bundle_product} 각 행의 {@code tier_ids} 개수 합.
     * 15 로 박아 두었더니 Apple One 두 줄을 넣자마자 깨졌다(2026-09-20) — 같은 파일 위쪽 주석이
     * "건수를 박아두면 시드가 늘 때마다 깨진다" 라고 이미 적고 있었는데 이 줄만 예외였다.
     */
    private static int seedBundleItemCount() throws IOException {
        var resource = CombinedCatalogCsv.bundled().get("bundle_product");
        try (var lines = resource.getContentAsString(StandardCharsets.UTF_8).lines()) {
            return lines.skip(1).filter(line -> !line.isBlank())
                    .mapToInt(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')).split(",").length)
                    .sum();
        }
    }

    private static ByteArrayResource csv(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }
}
