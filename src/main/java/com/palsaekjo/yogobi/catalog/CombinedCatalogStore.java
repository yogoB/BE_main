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
    private final CatalogAuditLog audit;
    private final Path file;

    public CombinedCatalogStore(CatalogSeedLoader loader, CatalogAuditLog audit,
                                @Value("${yogobi.catalog.combined-csv:}") String path) {
        this.loader = loader;
        this.audit = audit;
        this.file = path.isBlank() ? null : Path.of(path);
    }

    /** 합본 CRUD가 켜져 있는가. 경로가 없으면 기존 시드 경로만 쓴다(fail-soft). */
    public boolean enabled() {
        return file != null;
    }

    /**
     * 편집이 가능한 설정인가. **읽기는 경로가 없어도 클래스패스 폴백으로 계속 된다** — 막는 것은 쓰기뿐이다.
     *
     * <p>운영에는 `yogobi.catalog.combined-csv` 가 설정돼 있지 않아 이 조건이 실제로 발생한다(2026-09-17 확인).
     * 가드가 없던 동안에는 승인 시점에 `IllegalStateException` 이 터져 **500** 이 나갔다. 설정 문제를
     * 서버 오류로 보여주면 운영자는 자기 입력을 의심하게 된다 — 503 으로 "지금은 편집할 수 없다"고 말한다.
     */
    public void requireWritable() {
        if (!enabled()) throw new ApiException("YGB-CAT-503", 503,
                "카탈로그 원본 파일 경로가 설정되지 않아 지금은 편집할 수 없어요. 조회는 그대로 됩니다.", null);
    }

    /**
     * 외부 원본(볼륨 파일)과 내장 원본(레포 CSV)이 갈라졌는가.
     *
     * <p><b>갈라지면 외부 파일이 이긴다</b>({@link #read()}). 관리자가 한 번이라도 승인하면 외부 파일이
     * 생기고, 그 뒤로는 레포 CSV 를 고쳐 배포해도 조용히 무시된다 — D-24 는 "CSV 가 단일 원본"이라고
     * 선언했는데 원본이 둘이 되는 것이다. 어느 쪽이 이겨야 하는지는 제품 결정이므로 여기서 고르지 않는다.
     * 대신 <b>갈라졌다는 사실을 보이게</b> 만든다 — 조용한 분기가 가장 나쁘다.
     */
    public synchronized boolean diverged() {
        if (!enabled() || !Files.exists(file)) return false;
        try {
            return !java.util.Arrays.equals(Files.readAllBytes(file),
                    new ClassPathResource("db/seed/catalog_combined.csv").getContentAsByteArray());
        } catch (IOException e) {
            return false;   // 읽지 못하면 read() 가 같은 오류로 제대로 실패한다. 여기서 두 번 말하지 않는다.
        }
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
    public Map<String, String> create(String dataset, Map<String, String> values, long actorId) {
        return create(dataset, values, actorId, null);
    }

    /** {@code trace} 는 감사 기록에 함께 남길 경위(승인 요청 번호·제안자 등). D-28. */
    public Map<String, String> create(String dataset, Map<String, String> values, long actorId, String trace) {
        return mutate(dataset, actorId, trace, (columns, rows) -> {
            String key = key(dataset, columns, values);
            if (indexOf(dataset, columns, rows, key) >= 0)
                throw ApiException.conflict("이미 존재하는 행입니다: " + key);
            String row = CombinedCatalogCsv.row(ordered(columns, values));
            rows.add(row);
            return new Mutation(values, CatalogAuditLog.Action.CREATE, key, null, row);
        });
    }

    /** 행 수정. 보낸 필드만 덮어쓰고 나머지는 유지한다(부분 수정). */
    public Map<String, String> update(String dataset, String key, Map<String, String> values, long actorId) {
        return update(dataset, key, values, actorId, null);
    }

    public Map<String, String> update(String dataset, String key, Map<String, String> values,
                                      long actorId, String trace) {
        return mutate(dataset, actorId, trace, (columns, rows) -> {
            int index = indexOf(dataset, columns, rows, key);
            if (index < 0) throw ApiException.planNotFound("행을 찾을 수 없습니다: " + key);
            String before = rows.get(index);
            Map<String, String> merged = toMap(columns, before);
            merged.putAll(values);
            if (!key(dataset, columns, merged).equals(key))
                throw ApiException.conflict("키 필드는 수정할 수 없습니다. 삭제 후 다시 추가하세요.");
            String after = CombinedCatalogCsv.row(ordered(columns, merged));
            rows.set(index, after);
            return new Mutation(merged, CatalogAuditLog.Action.UPDATE, key, before, after);
        });
    }

    /** 행 삭제. 파일에서 지우면 DB 재적재에서 active=false로 내려간다(회원 참조는 보존). */
    public void delete(String dataset, String key, long actorId) {
        delete(dataset, key, actorId, null);
    }

    public void delete(String dataset, String key, long actorId, String trace) {
        mutate(dataset, actorId, trace, (columns, rows) -> {
            int index = indexOf(dataset, columns, rows, key);
            if (index < 0) throw ApiException.planNotFound("행을 찾을 수 없습니다: " + key);
            String before = rows.remove(index);
            return new Mutation(Map.of(), CatalogAuditLog.Action.DELETE, key, before, null);
        });
    }

    /** 변경 결과와 감사 기록에 필요한 전/후 행. */
    private record Mutation(Map<String, String> result, CatalogAuditLog.Action action,
                            String key, String before, String after) {
    }

    private interface Change {
        Mutation apply(List<String> columns, List<String> rows);
    }

    /**
     * 파일 → 변경 → 파일 쓰기 → DB 반영 → 감사 기록. DB가 실패하면 파일을 원상복구하고 FAILED 로 남긴 뒤 올린다.
     * 변경 전 검증(404·409)에서 막힌 요청은 원본을 건드리지 않았으므로 기록하지 않는다 — 감사 대상은 실제 쓰기 시도다.
     */
    private synchronized Map<String, String> mutate(String dataset, long actorId, String trace, Change change) {
        Map<String, Section> sections = read();
        Section section = section(sections, dataset);
        List<String> columns = CombinedCatalogCsv.fields(section.header());
        var rows = new ArrayList<>(section.rows());
        Mutation mutation = change.apply(columns, rows);

        var updated = new LinkedHashMap<>(sections);
        updated.put(dataset, new Section(section.header(), rows));
        String text = CombinedCatalogCsv.write(updated);

        String previous = CombinedCatalogCsv.write(sections);
        write(text);
        try {
            reload(updated);
        } catch (RuntimeException | SQLException | IOException e) {
            write(previous); // DB 실패 시 파일을 되돌린다 — 원본과 투영이 갈라지면 안 된다.
            audit.failed(actorId, mutation.action(), dataset, mutation.key(),
                    mutation.before(), mutation.after(), detail(trace, e.getClass().getSimpleName()));
            throw new IllegalStateException("DB 반영 실패 — 변경을 되돌렸습니다", e);
        }
        audit.applied(actorId, mutation.action(), dataset, mutation.key(),
                mutation.before(), mutation.after(), trace);
        return mutation.result();
    }

    /** 경위와 사유를 한 칸에 담는다 — 감사 표의 detail 하나로 "왜·어떤 승인으로" 를 함께 본다. */
    private static String detail(String trace, String reason) {
        if (trace == null) return reason;
        return reason == null ? trace : trace + " · " + reason;
    }

    /** 합본 전체를 DB에 반영한다. 기존 스냅샷 적재 경로를 그대로 쓴다(단일 트랜잭션·미포함 행 active=false). */
    public void reload(Map<String, Section> sections) throws SQLException, IOException {
        loader.loadSnapshot(CombinedCatalogCsv.toResources(sections));
    }

    public void reload() throws SQLException, IOException {
        reload(read());
    }

    private void write(String text) {
        requireWritable();   // 컨트롤러 가드를 지나온 경로라도 여기서 한 번 더 막는다(호출자가 늘어날 수 있다)
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
