package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 합본 CSV 파서: 섹션 분리·왕복 보존·필드 이스케이프·구성 오류 거부. 실제 원천 파일로 검증한다. */
class CombinedCatalogCsvTest {
    private String source() throws Exception {
        return new ClassPathResource("db/seed/catalog_combined.csv").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void parsesEveryDatasetFromTheRealFile() throws Exception {
        var sections = CombinedCatalogCsv.parse(source());
        assertThat(sections.keySet()).containsExactlyInAnyOrderElementsOf(CombinedCatalogCsv.DATASETS);
        assertThat(sections.get("mobile_plan").header())
                .startsWith("carrier,plan_name,network_type,base_price");
        assertThat(sections.get("mobile_plan").rows()).hasSize(1706);
        assertThat(sections.get("subscription_service").rows()).hasSize(6);
        assertThat(sections.get("subscription_tier").rows()).hasSize(17);
        assertThat(sections.get("plan_benefit").rows()).hasSize(46);
        assertThat(sections.get("bundle_product").rows()).hasSize(7);
    }

    @Test
    void writeThenParseKeepsEveryRow() throws Exception {
        var original = CombinedCatalogCsv.parse(source());
        var roundTripped = CombinedCatalogCsv.parse(CombinedCatalogCsv.write(original));
        assertThat(roundTripped).isEqualTo(original);
    }

    /** 로더로 넘어가는 리소스에는 주석·섹션 마커가 없어야 한다 — COPY는 '#'을 데이터로 읽는다. */
    @Test
    void resourcesAreCleanCsvForCopy() throws Exception {
        var resources = CombinedCatalogCsv.toResources(source());
        for (String dataset : CombinedCatalogCsv.DATASETS) {
            String csv = resources.get(dataset).getContentAsString(StandardCharsets.UTF_8);
            assertThat(csv).doesNotContain("#@ ").doesNotStartWith("#");
            assertThat(csv.lines().filter(line -> line.startsWith("#"))).isEmpty();
        }
    }

    @Test
    void quotedCommasSurviveFieldSplitAndJoin() {
        String row = CombinedCatalogCsv.row(List.of("1", "티빙x웨이브", "7000", "TVING", "6,10"));
        assertThat(row).isEqualTo("1,티빙x웨이브,7000,TVING,\"6,10\"");
        assertThat(CombinedCatalogCsv.fields(row)).containsExactly("1", "티빙x웨이브", "7000", "TVING", "6,10");
        assertThat(CombinedCatalogCsv.fields("a,\"say \"\"hi\"\"\",b")).containsExactly("a", "say \"hi\"", "b");
    }

    @Test
    void rejectsMissingDatasetAndStrayRows() {
        assertThatThrownBy(() -> CombinedCatalogCsv.parse("#@ mobile_plan\ncarrier\nSKT\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("데이터셋 구성 오류");
        assertThatThrownBy(() -> CombinedCatalogCsv.parse("carrier,plan_name\nSKT,요고\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("섹션 밖");
    }
}
