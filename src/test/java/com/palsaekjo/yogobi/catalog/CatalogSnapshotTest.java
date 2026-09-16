package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "CATALOG_CSV_DIR=")
@Testcontainers
class CatalogSnapshotTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired CatalogSeedLoader loader;
    @Autowired CatalogReader reader;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    static final String MOBILE_HEADER = "carrier,plan_name,network_type,base_price,data_mb,voice_min,sms_cnt,contract_discount_12m,contract_discount_24m,age_limit,source_url,collected_at\n";
    static final String BENEFIT_HEADER = "carrier,plan_name,service_id,tier_id,benefit_type,discount_value,is_exclusive,exclusive_group,valid_from,valid_to,source_url,collected_at\n";

    Map<String, Resource> snapshot(long price) {
        var files = new LinkedHashMap<String, Resource>();
        var parts = bundled();
        for (String name : new String[]{"subscription_service", "subscription_tier", "bundle_product"})
            files.put(name, parts.get(name));
        files.put("mobile_plan", csv(MOBILE_HEADER + "SKT,CSV 요금제,5G," + price + ",100000,100,100,,,,https://example.com,2026-09-01\n"));
        files.put("plan_benefit", csv(BENEFIT_HEADER));
        return files;
    }
    static Resource csv(String content) { return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)); }

    /** 카탈로그 원본은 합본 한 파일이다. 예외는 테스트 실패로 드러나야 하므로 감싸서 던진다. */
    static Map<String, Resource> bundled() {
        try {
            return CombinedCatalogCsv.bundled();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("내장 합본 시드를 읽지 못했습니다", e);
        }
    }

    @Test void snapshotUpdatesInPlaceAndRetiresWithoutBreakingMemberReference() throws Exception {
        loader.loadSnapshot(snapshot(55000));
        long id = reader.listPlans().get(0).id();
        jdbc.update("INSERT INTO app_user(email,password_hash,current_plan_id) VALUES ('csv@example.com','test',?)", id);
        loader.loadSnapshot(snapshot(51000));
        assertThat(reader.listPlans().get(0).id()).isEqualTo(id);
        assertThat(reader.listPlans().get(0).basePrice()).isEqualTo(51000);
        var removed = snapshot(51000); removed.put("mobile_plan", csv(MOBILE_HEADER));
        loader.loadSnapshot(removed);
        assertThat(reader.listPlans()).isEmpty();
        assertThat(reader.findCandidatePlans(1, null)).isEmpty();
        assertThat(reader.findPlanById(id)).isPresent();
        assertThat(jdbc.queryForObject("SELECT current_plan_id FROM app_user WHERE email='csv@example.com'", Long.class)).isEqualTo(id);
        loader.loadSnapshot(snapshot(49000));
        assertThat(reader.listPlans().get(0).id()).isEqualTo(id);
    }

    @Test void failureInLastDatasetRollsBackEarlierPriceChanges() throws Exception {
        loader.loadSnapshot(snapshot(55000));
        var broken = snapshot(12345);
        broken.put("plan_benefit", csv(BENEFIT_HEADER + "SKT,없는 요금제,1,2,FREE,,false,,,,https://example.com,2026-09-01\n"));
        assertThatThrownBy(() -> loader.loadSnapshot(broken)).isInstanceOf(IllegalStateException.class);
        assertThat(reader.listPlans().get(0).basePrice()).isEqualTo(55000);
    }

    @Test void syncVerifiesApprovedBytesAndAppliesOnce() throws Exception {
        var files = snapshot(47000);
        var hashes = new LinkedHashMap<String, String>();
        for (var entry : files.entrySet()) hashes.put(entry.getKey(), hash(entry.getValue().getContentAsByteArray()));
        ObjectMapper json = new ObjectMapper();
        byte[] manifest = json.writeValueAsBytes(Map.of("files", hashes, "approved_by", "검수자"));
        String revision = hash(manifest);
        Path version = directory.resolve("revisions").resolve(revision); Files.createDirectories(version);
        Files.write(version.resolve("manifest.json"), manifest);
        for (var entry : files.entrySet()) Files.write(version.resolve(entry.getKey() + ".csv"), entry.getValue().getContentAsByteArray());
        Files.writeString(directory.resolve("current"), revision);
        var sync = new CatalogCsvSync(loader, json, directory.toString());
        Files.writeString(version.resolve("mobile_plan.csv"), "tampered");
        assertThatThrownBy(sync::sync).isInstanceOf(IllegalArgumentException.class);
        Files.write(version.resolve("mobile_plan.csv"), files.get("mobile_plan").getContentAsByteArray());
        assertThat(sync.sync()).isTrue();
        assertThat(sync.sync()).isFalse();
        assertThat(reader.listPlans().get(0).basePrice()).isEqualTo(47000);
    }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
