package com.palsaekjo.yogobi.catalog;


import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * 합본 CSV(단일 원본) ↔ 데이터셋별 CSV 변환. D-21.
 *
 * <p>형식: {@code #@ <dataset>} 줄이 섹션 경계다. 섹션의 첫 비주석 줄은 헤더, 나머지가 데이터다.
 * 그 밖의 {@code #}로 시작하는 줄은 주석이며 왕복(파싱→직렬화) 시 보존하지 않는다.
 * 적재는 기존 {@link CatalogSeedLoader}가 데이터셋별 {@link Resource}로 그대로 처리한다 —
 * COPY는 주석을 모르므로 여기서 섹션을 떼어 순수 CSV로 넘긴다.
 */
public final class CombinedCatalogCsv {
    /** 적재 순서. 혜택은 요금제를, 번들은 티어를 참조하므로 순서를 바꾸지 않는다. */
    public static final List<String> DATASETS = List.of(
            "mobile_plan", "subscription_service", "subscription_tier", "plan_benefit", "bundle_product");
    private static final String MARKER = "#@ ";
    private static final String HEADER = """
            # 요고비 카탈로그 원천 데이터셋 — 단일 원본(source of truth). DB는 이 파일의 투영이며 CRUD는 이 파일을 되쓴다.
            # 형식: '#@ <dataset>' 줄이 섹션 경계다. 각 섹션의 첫 비주석 줄은 CSV 헤더, 이후가 데이터 행이다. 그 밖의 '#' 줄은 주석.
            # 데이터셋: mobile_plan(통신요금) · subscription_service/subscription_tier(구독서비스) · plan_benefit(혜택) · bundle_product(번들).
            """;

    private CombinedCatalogCsv() {
    }

    /** 섹션 하나 — 헤더 1줄과 데이터 행들. 행은 CSV 원문 그대로(따옴표·쉼표 보존). */
    public record Section(String header, List<String> rows) {
        public Section {
            rows = List.copyOf(rows);
        }
    }

    /**
     * 합본을 데이터셋별 섹션으로 나눈다. 다섯 데이터셋이 모두 있어야 하며, 순서·중복·빈 섹션은 오류다
     * — 부분 적재는 카탈로그 결손(잘못된 추천)으로 이어지므로 조용히 넘기지 않는다.
     */
    public static Map<String, Section> parse(String text) {
        var sections = new LinkedHashMap<String, Section>();
        String current = null;
        var rows = new ArrayList<String>();
        for (String line : text.split("\n", -1)) {
            String value = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (value.startsWith(MARKER)) {
                if (current != null) sections.put(current, section(current, rows));
                current = value.substring(MARKER.length()).strip();
                if (sections.containsKey(current)) throw new IllegalArgumentException("중복 섹션: " + current);
                rows = new ArrayList<>();
            } else if (!value.isBlank() && !value.startsWith("#")) {
                if (current == null) throw new IllegalArgumentException("섹션 밖의 데이터 행이 있습니다");
                rows.add(value);
            }
        }
        if (current != null) sections.put(current, section(current, rows));
        if (!sections.keySet().equals(java.util.Set.copyOf(DATASETS)))
            throw new IllegalArgumentException("데이터셋 구성 오류 — 필요: " + DATASETS + ", 실제: " + sections.keySet());
        return sections;
    }

    private static Section section(String name, List<String> rows) {
        if (rows.isEmpty()) throw new IllegalArgumentException("헤더가 없는 섹션: " + name);
        return new Section(rows.get(0), rows.subList(1, rows.size()));
    }

    /** 섹션들을 합본 텍스트로 직렬화한다. {@link #DATASETS} 순서로 쓴다. */
    public static String write(Map<String, Section> sections) {
        var out = new StringBuilder(HEADER);
        for (String dataset : DATASETS) {
            Section section = sections.get(dataset);
            if (section == null) throw new IllegalArgumentException("누락된 데이터셋: " + dataset);
            out.append(MARKER).append(dataset).append('\n').append(section.header()).append('\n');
            for (String row : section.rows()) out.append(row).append('\n');
        }
        return out.toString();
    }

    /** 기존 로더가 쓰는 데이터셋별 CSV 리소스. 주석·섹션 마커는 제거된 순수 CSV다. */
    public static Map<String, Resource> toResources(Map<String, Section> sections) {
        var out = new LinkedHashMap<String, Resource>();
        for (String dataset : DATASETS) {
            Section section = sections.get(dataset);
            var csv = new StringBuilder(section.header()).append('\n');
            for (String row : section.rows()) csv.append(row).append('\n');
            out.put(dataset, new ByteArrayResource(csv.toString().getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    public static Map<String, Resource> toResources(String text) {
        return toResources(parse(text));
    }

    /** CSV 한 줄을 필드로 나눈다(RFC4180: 따옴표 안의 쉼표·이스케이프 따옴표 지원). */
    public static List<String> fields(String row) {
        var out = new ArrayList<String>();
        var value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (quoted) {
                if (c != '"') value.append(c);
                else if (i + 1 < row.length() && row.charAt(i + 1) == '"') { value.append('"'); i++; }
                else quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == ',') { out.add(value.toString()); value.setLength(0); }
            else value.append(c);
        }
        out.add(value.toString());
        return List.copyOf(out);
    }

    /** 필드들을 CSV 한 줄로 만든다. 쉼표·따옴표·개행이 있으면 따옴표로 감싼다. */
    public static String row(List<String> values) {
        var out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            String value = values.get(i) == null ? "" : values.get(i);
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0)
                out.append('"').append(value.replace("\"", "\"\"")).append('"');
            else out.append(value);
        }
        return out.toString();
    }

    /** 합본 바이트를 읽어 섹션으로 나눈다(편의). */
    public static Map<String, Section> parse(byte[] bytes) {
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }
}
