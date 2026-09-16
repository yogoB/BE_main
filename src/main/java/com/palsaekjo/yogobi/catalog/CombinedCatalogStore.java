package com.palsaekjo.yogobi.catalog;

import com.palsaekjo.yogobi.catalog.CombinedCatalogCsv.Section;
import com.palsaekjo.yogobi.common.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 합본 CSV(단일 원본)의 CRUD와 DB 반영. D-21.
 *
 * <p>쓰기 순서: 파일 먼저(임시파일 + 원자적 교체), 그다음 DB 재적재. DB 반영이 실패하면 파일을 되돌려
 * 둘이 갈라지지 않게 한다 — 원본은 어디까지나 파일이고 DB는 그 투영이다.
 * 모든 변경은 프로세스 내에서 직렬화한다(synchronized) — 동시 쓰기로 행이 유실되지 않게.
 *
 * <p>ponytail: 파일 전체 재작성 + 전체 재적재다. 1,700행 규모에서는 충분하고, 커지면 행 단위 반영으로 바꾼다.
 */
@Component
public class CombinedCatalogStore {
    private static final Logger log = LoggerFactory.getLogger(CombinedCatalogStore.class);
    /** 행 동일성 판단 키. CSV에 id가 있으면 id, 없으면 자연키. */
    private static final Map<String, List<String>> KEYS = Map.of(
            "mobile_plan", List.of("carrier", "plan_name"),
            "subscription_service", List.of("id"),
            "subscription_tier", List.of("id"),
            "plan_benefit", List.of("carrier", "plan_name", "service_id", "tier_id"),
            "bundle_product", List.of("id"));

    private final CatalogSeedLoader loader;
    private final Path file;

    public CombinedCatalogStore(CatalogSeedLoader loader,
                                @Value("${yogobi.catalog.combined-csv:}") String path) {
        this.loader = loader;
        this.file = path.isBlank() ? null : Path.of(path);
    }

    /** 합본 CRUD가 켜져 있는가. 경로가 없으면 기존 시드 경로만 쓴다(fail-soft). */
    public boolean enabled() {
        return file != null;
    }

    public synchronized Map<String, Section> read() {
        try {
            byte[] bytes = enabled() && Files.exists(file)
                    ? Files.readAllBytes(file)
                    : new ClassPathResource("db/seed/catalog_combined.csv").getContentAsByteArray();
            return CombinedCatalogCsv.parse(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("합본 CSV를 읽지 못했습니다", e);
        }
    }

    public List<String> datasets() {
        return CombinedCatalogCsv.DATASETS;
    }

    /** 한 데이터셋의 행을 헤더 기준 맵으로 돌려준다(조회용). */
    public List<Map<String, String>> rows(String dataset) {
        Section section = section(read(), dataset);
        List<String> columns = CombinedCatalogCsv.fields(section.header());
        var out = new ArrayList<Map<String, String>>();
        for (String row : section.rows()) out.add(toMap(columns, row));
        return out;
    }

    /** 행 추가. 같은 키가 이미 있으면 409다 — 조용한 덮어쓰기는 데이터 유실이다. */
    public Map<String, String> create(String dataset, Map<String, String> values) {
        return mutate(dataset, (columns, rows) -> {
            String key = key(dataset, columns, values);
            if (indexOf(dataset, columns, rows, key) >= 0)
                throw ApiException.conflict("이미 존재하는 행입니다: " + key);
            rows.add(CombinedCatalogCsv.row(ordered(columns, values)));
            return values;
        });
    }

    /** 행 수정. 보낸 필드만 덮어쓰고 나머지는 유지한다(부분 수정). */
    public Map<String, String> update(String dataset, String key, Map<String, String> values) {
        return mutate(dataset, (columns, rows) -> {
            int index = indexOf(dataset, columns, rows, key);
            if (index < 0) throw ApiException.planNotFound("행을 찾을 수 없습니다: " + key);
            Map<String, String> merged = toMap(columns, rows.get(index));
            merged.putAll(values);
            if (!key(dataset, columns, merged).equals(key))
                throw ApiException.conflict("키 필드는 수정할 수 없습니다. 삭제 후 다시 추가하세요.");
            rows.set(index, CombinedCatalogCsv.row(ordered(columns, merged)));
            return merged;
        });
    }

    /** 행 삭제. 파일에서 지우면 DB 재적재에서 active=false로 내려간다(회원 참조는 보존). */
    public void delete(String dataset, String key) {
        mutate(dataset, (columns, rows) -> {
            int index = indexOf(dataset, columns, rows, key);
            if (index < 0) throw ApiException.planNotFound("행을 찾을 수 없습니다: " + key);
            rows.remove(index);
            return Map.of();
        });
    }

    private interface Change {
        Map<String, String> apply(List<String> columns, List<String> rows);
    }

    /** 파일 → 변경 → 파일 쓰기 → DB 반영. DB가 실패하면 파일을 원상복구하고 예외를 올린다. */
    private synchronized Map<String, String> mutate(String dataset, Change change) {
        Map<String, Section> sections = read();
        Section section = section(sections, dataset);
        List<String> columns = CombinedCatalogCsv.fields(section.header());
        var rows = new ArrayList<>(section.rows());
        Map<String, String> result = change.apply(columns, rows);

        var updated = new LinkedHashMap<>(sections);
        updated.put(dataset, new Section(section.header(), rows));
        String text = CombinedCatalogCsv.write(updated);

        String previous = CombinedCatalogCsv.write(sections);
        write(text);
        try {
            reload(updated);
        } catch (RuntimeException | SQLException | IOException e) {
            write(previous); // DB 실패 시 파일을 되돌린다 — 원본과 투영이 갈라지면 안 된다.
            throw new IllegalStateException("DB 반영 실패 — 변경을 되돌렸습니다", e);
        }
        return result;
    }

    /** 합본 전체를 DB에 반영한다. 기존 스냅샷 적재 경로를 그대로 쓴다(단일 트랜잭션·미포함 행 active=false). */
    public void reload(Map<String, Section> sections) throws SQLException, IOException {
        loader.loadSnapshot(CombinedCatalogCsv.toResources(sections));
    }

    public void reload() throws SQLException, IOException {
        reload(read());
    }

    private void write(String text) {
        if (!enabled()) throw new IllegalStateException(
                "합본 CSV 경로(yogobi.catalog.combined-csv)가 설정되지 않아 쓸 수 없습니다");
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "catalog", ".csv");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("합본 CSV 갱신: {}", file);
        } catch (IOException e) {
            throw new IllegalStateException("합본 CSV를 쓰지 못했습니다", e);
        }
    }

    private static Section section(Map<String, Section> sections, String dataset) {
        Section section = sections.get(dataset);
        if (section == null) throw ApiException.planNotFound("알 수 없는 데이터셋: " + dataset);
        return section;
    }

    /** 행 키 — 키 컬럼 값을 '|'로 이은 문자열. URL 경로에 그대로 쓴다. */
    static String key(String dataset, List<String> columns, Map<String, String> values) {
        var parts = new ArrayList<String>();
        for (String column : KEYS.get(dataset)) {
            if (!columns.contains(column)) throw new IllegalStateException("키 컬럼 없음: " + column);
            parts.add(values.getOrDefault(column, "").strip());
        }
        return String.join("|", parts);
    }

    private static int indexOf(String dataset, List<String> columns, List<String> rows, String key) {
        for (int i = 0; i < rows.size(); i++)
            if (key(dataset, columns, toMap(columns, rows.get(i))).equals(key)) return i;
        return -1;
    }

    private static Map<String, String> toMap(List<String> columns, String row) {
        List<String> values = CombinedCatalogCsv.fields(row);
        var out = new LinkedHashMap<String, String>();
        for (int i = 0; i < columns.size(); i++) out.put(columns.get(i), i < values.size() ? values.get(i) : "");
        return out;
    }

    /** 헤더 순서대로 값을 정렬한다. 헤더에 없는 필드는 거부한다(오타가 조용히 사라지지 않게). */
    private static List<String> ordered(List<String> columns, Map<String, String> values) {
        for (String field : values.keySet())
            if (!columns.contains(field)) throw ApiException.requiredMissing(field, "알 수 없는 필드: " + field);
        var out = new ArrayList<String>();
        for (String column : columns) out.add(values.getOrDefault(column, ""));
        return out;
    }
}
