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

    /**
     * G-93 a·b. data_mb 는 요금제 이름이 밝힌 양이다. '매일 N GB'는 (기본량 + 30×N) GB, 'NGB+'(그 뒤 속도 제한)는 N GB.
     * 같은 '매일 5GB'가 통신사마다 0·5,120·153,600 으로 섞여 있었고, 'LTE 무제한 7GB+' 같은 38행이 무제한(999,999)으로 적혀
     * 50GB 를 쓰는 사람에게 7GB 뒤 속도 제한 요금제가 무제한처럼 추천됐다(운영 사용자 흐름 검증 2026-10-01).
     */
    @Test
    void dataFollowsWhatThePlanNameStates_g93() throws Exception {
        var section = CombinedCatalogCsv.parse(source()).get("mobile_plan");
        var header = CombinedCatalogCsv.fields(section.header());
        int name = header.indexOf("plan_name"), data = header.indexOf("data_mb");
        var daily = java.util.regex.Pattern.compile("(?:매일|(?<![가-힣])일)\\s*(\\d+(?:\\.\\d+)?)\\s*G(?:B)?");
        var baseBeforeDaily = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)GB\\s*\\+\\s*(?:매일|일)");
        var qos = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)GB\\+");
        var wrong = new java.util.ArrayList<String>();
        for (String row : section.rows()) {
            var f = CombinedCatalogCsv.fields(row);
            String plan = f.get(name);
            long mb = Long.parseLong(f.get(data));
            var d = daily.matcher(plan);
            if (d.find()) {
                var b = baseBeforeDaily.matcher(plan);
                double gb = (b.find() ? Double.parseDouble(b.group(1)) : 0) + 30 * Double.parseDouble(d.group(1));
                if (mb != (long) (gb * 1024)) wrong.add(plan + " " + mb);
            } else if (mb == 999_999 && qos.matcher(plan).find()) {
                wrong.add(plan + " 무제한으로 적힘");
            }
        }
        assertThat(wrong).isEmpty();
    }

    /** G-93 c. 월 금액이 사용량에 따라 정해지는 종량제 등급은 싣지 않는다 — 기본료만 월 정가로 계산되어 금액이 과소였다(지니뮤직 110원). */
    @Test
    void noUsageBilledTierIsListedAsAMonthlyPrice_g93c() throws Exception {
        var section = CombinedCatalogCsv.parse(source()).get("subscription_tier");
        int note = CombinedCatalogCsv.fields(section.header()).indexOf("note");
        assertThat(section.rows()).noneMatch(row -> CombinedCatalogCsv.fields(row).get(note).contains("과금"));
    }

    @Test
    void parsesEveryDatasetFromTheRealFile() throws Exception {
        var sections = CombinedCatalogCsv.parse(source());
        assertThat(sections.keySet()).containsExactlyInAnyOrderElementsOf(CombinedCatalogCsv.DATASETS);
        assertThat(sections.get("mobile_plan").header())
                .startsWith("carrier,plan_name,network_type,base_price");
        // 건수는 박지 않는다(시드가 계속 는다). 개별 시드 파일은 없앴으므로(원본은 이 합본 하나)
        // 대조 대신 섹션이 비어 있지 않고 헤더에 열이 갖춰졌는지 본다.
        for (String dataset : CombinedCatalogCsv.DATASETS) {
            assertThat(sections.get(dataset).rows()).as(dataset).isNotEmpty();
            assertThat(sections.get(dataset).header()).as(dataset).contains(",");
        }
        assertThat(sections.get("subscription_tier").header()).endsWith(",currency,tax_included");
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
