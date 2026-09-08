package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(2);
        assertSnapshot();
        loader.run(null);
        assertSnapshot();
        assertThat(jdbc.queryForList("SELECT tier_id FROM bundle_item WHERE bundle_id = 4 ORDER BY tier_id", Long.class))
                .containsExactly(8L, 12L);
        assertThat(jdbc.queryForObject("SELECT concurrent_streams FROM subscription_tier WHERE id = 16", Integer.class))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT nextval('subscription_tier_id_seq')", Long.class)).isEqualTo(18L);

        var services = new ClassPathResource("db/seed/subscription_service.csv");
        var tiers = new ClassPathResource("db/seed/subscription_tier.csv");
        var bundles = new ClassPathResource("db/seed/bundle_product.csv");
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
        assertThat(jdbc.queryForObject("SELECT nextval('subscription_tier_id_seq')", Long.class)).isEqualTo(19L);
    }

    private void assertSnapshot() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_service", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_tier", Integer.class)).isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bundle_product", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bundle_item", Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT price FROM subscription_tier WHERE id = 2", Long.class)).isEqualTo(13500L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mobile_plan", Integer.class)).isZero();
    }

    private static ByteArrayResource csv(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }
}
