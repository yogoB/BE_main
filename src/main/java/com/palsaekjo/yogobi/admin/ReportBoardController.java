package com.palsaekjo.yogobi.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 제보 게시판(운영자 전용). 두 표를 한 목록으로 본다 —
 * 상품을 지명한 제보(`catalog_report`, D-18)와 화면·기타 제보(`service_report`, D-41)다.
 * 지표는 이미 둘을 합쳐 세고 있었는데(BackofficeMetrics) 정작 <b>내용을 볼 화면이 없었다</b>.
 *
 * <p><b>회원 신원은 내보내지 않는다.</b> `service_report.user_id` 는 쿠폰 귀속용이지 제보자 식별용이
 * 아니다(D-42). 운영자가 제보를 처리하는 데 필요한 것은 본문·대상·출처지 누가 썼는지가 아니다.
 * 쿠폰 발급 여부조차 싣지 않는다 — 처리 판단에 쓰이지 않는 값이다.
 *
 * <p>공개 조회는 여전히 없다(`docs/catalog-data.md`). 여기는 `/api/v1/admin/**` 아래라
 * SecurityConfig 가 ROLE_ADMIN 으로 막고, 상태 변경은 CSRF 기본 보호를 그대로 받는다.
 */
@RestController
@RequestMapping("/api/v1/admin/reports")
public class ReportBoardController {

    /** 표 이름은 코드가 가진 allowlist 다. 경로 변수를 SQL 식별자로 쓰지 않는다. */
    private static final Map<String, String> TABLES =
            Map.of("CATALOG", "catalog_report", "SERVICE", "service_report");
    private static final Set<String> STATUSES = Set.of("PENDING", "RESOLVED", "REJECTED");
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final AdminActions actions;

    public ReportBoardController(JdbcTemplate jdbc, AdminActions actions) {
        this.jdbc = jdbc;
        this.actions = actions;
    }

    /**
     * 제보 한 건. {@code kind} 로 두 출처를 구분한다.
     * {@code detail} 은 카탈로그 제보의 오류 항목(PRICE·DATA…) 또는 화면 제보의 종류(SYSTEM·OTHER)다.
     * {@code target} 은 카탈로그 제보가 지명한 상품 이름이고, 화면 제보는 대신 {@code pageUrl} 이 있다.
     */
    public record Report(String kind, UUID id, String status, Instant createdAt, String detail,
                         String targetType, Long targetId, String target, String description,
                         String pageUrl, String sourceUrl, String note, Instant updatedAt) { }

    @GetMapping
    public ApiResponse<List<Report>> list(@RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "50") int limit) {
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.requiredMissing("status", "처리 상태를 확인해 주세요.");
        }
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        // 상품 이름은 target_type 에 맞는 표에서만 채워진다. 이름이 비면 그 상품이 이미 지워진 것이다.
        String sql = """
                SELECT * FROM (
                    SELECT 'CATALOG' AS kind, r.id, r.status, r.created_at, r.field AS detail,
                           r.target_type, r.target_id, COALESCE(mp.name, ss.name, st.name, bp.name) AS target,
                           r.description, NULL::varchar AS page_url, r.source_url, r.note, r.updated_at
                    FROM catalog_report r
                    LEFT JOIN mobile_plan mp ON r.target_type = 'MOBILE_PLAN' AND mp.id = r.target_id
                    LEFT JOIN subscription_service ss ON r.target_type = 'SUBSCRIPTION_SERVICE' AND ss.id = r.target_id
                    LEFT JOIN subscription_tier st ON r.target_type = 'SUBSCRIPTION_TIER' AND st.id = r.target_id
                    LEFT JOIN bundle_product bp ON r.target_type = 'BUNDLE_PRODUCT' AND bp.id = r.target_id
                    UNION ALL
                    SELECT 'SERVICE', s.id, s.status, s.created_at, s.category,
                           NULL::text, NULL::bigint, NULL::varchar, s.description, s.page_url, s.source_url, s.note, s.updated_at
                    FROM service_report s
                ) AS reports
                WHERE (CAST(? AS text) IS NULL OR status = CAST(? AS text))
                ORDER BY created_at DESC
                LIMIT ?
                """;
        return ApiResponse.ok(jdbc.query(sql, (rs, row) -> new Report(
                rs.getString("kind"), rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("detail"),
                rs.getString("target_type"), rs.getObject("target_id", Long.class), rs.getString("target"),
                rs.getString("description"), rs.getString("page_url"), rs.getString("source_url"),
                rs.getString("note"), rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant()),
                status, status, capped));
    }

    /**
     * 처리 상태를 바꾼다. 제보는 원본 카탈로그를 건드리지 않으므로(D-18) 여기서 바뀌는 것은
     * <b>운영자가 봤는지</b> 뿐이다. 실제 가격 수정은 카탈로그 변경 제안·승인(D-28)을 따로 탄다.
     */
    @PatchMapping("/{kind}/{id}")
    public ApiResponse<Map<String, Object>> updateStatus(@PathVariable String kind, @PathVariable UUID id,
                                                         @RequestBody JsonNode body, java.security.Principal principal) {
        String table = TABLES.get(kind);
        if (table == null) throw ApiException.requiredMissing("kind", "제보 종류를 확인해 주세요.");
        String status = body.path("status").asText(null);
        if (status == null || !STATUSES.contains(status)) {
            throw ApiException.requiredMissing("status", "처리 상태를 확인해 주세요.");
        }
        // 처리 메모(D-52 ⑤). 보내지 않으면 그대로, 빈 문자열이면 지운다.
        String note = body.hasNonNull("note") ? body.get("note").asText() : null;
        if (note != null && note.length() > 1000) throw ApiException.requiredMissing("note", "메모는 1,000자까지예요.");
        int updated = body.hasNonNull("note")
                ? jdbc.update("UPDATE " + table + " SET status = ?, note = NULLIF(?, ''), updated_at = now() WHERE id = ?", status, note, id)
                : jdbc.update("UPDATE " + table + " SET status = ?, updated_at = now() WHERE id = ?", status, id);
        if (updated == 0) throw new ApiException("YGB-REQ-404", 404, "제보를 찾을 수 없어요.", "id");
        actions.record(Long.parseLong(principal.getName()), "REPORT_" + status, kind + ":" + id, note);
        return ApiResponse.ok(Map.of("id", id, "status", status));
    }
}
