package com.palsaekjo.yogobi.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카탈로그 결손 처리 흐름(D-52 ④). {@code catalog_candidate} 는 추천 경로가 "우리에게 없다"고 적어 둔 것이고
 * {@code requested_cnt} 가 수집 우선순위다. 지금까지는 쌓이기만 했다 — 여기서 상태를 옮기고 메모를 남긴다.
 * 실제 데이터 반영은 카탈로그 변경 제안·승인(D-28)이 한다. 이 표는 "무엇을 먼저 할지"만 든다.
 */
@RestController
@RequestMapping("/api/v1/admin/gaps")
public class GapBoardController {
    private static final Set<String> STATUSES = Set.of("REQUESTED", "IN_PROGRESS", "PENDING", "VERIFIED", "REJECTED");
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final AdminActions actions;

    public GapBoardController(JdbcTemplate jdbc, AdminActions actions) {
        this.jdbc = jdbc;
        this.actions = actions;
    }

    public record Gap(long id, String kind, String queryText, String status, int requestedCount,
                      Instant lastRequestedAt, String note, Instant updatedAt) { }

    /** 요청 많은 순. 상태를 안 주면 처리 안 된 것(REQUESTED·IN_PROGRESS)만 — 그게 할 일 목록이다. */
    @GetMapping
    public ApiResponse<List<Gap>> list(@RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "50") int limit) {
        if (status != null && !STATUSES.contains(status)) throw ApiException.requiredMissing("status", "상태를 확인해 주세요.");
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return ApiResponse.ok(jdbc.query("""
                SELECT id, kind, query_text, status, requested_cnt, last_requested_at, note, updated_at
                FROM catalog_candidate
                WHERE (CAST(? AS text) IS NULL AND status IN ('REQUESTED', 'IN_PROGRESS')) OR status = CAST(? AS text)
                ORDER BY requested_cnt DESC, last_requested_at DESC
                LIMIT ?
                """, (rs, i) -> new Gap(rs.getLong("id"), rs.getString("kind"), rs.getString("query_text"),
                        rs.getString("status"), rs.getInt("requested_cnt"), rs.getTimestamp("last_requested_at").toInstant(),
                        rs.getString("note"), rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant()),
                status, status, capped));
    }

    @PatchMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id, @RequestBody JsonNode body, Principal principal) {
        String status = body.path("status").asText(null);
        if (status == null || !STATUSES.contains(status)) throw ApiException.requiredMissing("status", "상태를 확인해 주세요.");
        String note = body.hasNonNull("note") ? body.get("note").asText() : null;
        if (note != null && note.length() > 1000) throw ApiException.requiredMissing("note", "메모는 1,000자까지예요.");
        int updated = body.hasNonNull("note")
                ? jdbc.update("UPDATE catalog_candidate SET status = ?, note = NULLIF(?, ''), updated_at = now() WHERE id = ?", status, note, id)
                : jdbc.update("UPDATE catalog_candidate SET status = ?, updated_at = now() WHERE id = ?", status, id);
        if (updated == 0) throw new ApiException("YGB-REQ-404", 404, "결손 항목을 찾을 수 없어요.", "id");
        actions.record(Long.parseLong(principal.getName()), "GAP_" + status, "gap:" + id, note);
        return ApiResponse.ok(Map.of("id", id, "status", status));
    }
}
