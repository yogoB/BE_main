package com.palsaekjo.yogobi.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.palsaekjo.yogobi.common.ApiException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 카탈로그 원본 변경 제안·승인(D-28). 쓰기는 즉시 반영되지 않고 제안으로 쌓이며, 승인해야 반영된다.
 *
 * <p>자기 승인은 허용한다 — 운영자가 1명일 수 있어 두 번째 사람을 요구하면 모든 변경이 막힌다.
 * 대신 **제안자와 승인자를 따로 남겨** 나중에 정책만 조이면 되게 한다.
 * 승인 시점에 실제 반영을 하므로, 반영이 실패하면 제안은 FAILED 로 닫고 원본은 그대로 둔다(되돌림은 store 가 한다).
 */
@Service
public class CatalogChangeRequests {
    private static final Logger log = LoggerFactory.getLogger(CatalogChangeRequests.class);
    private static final int PAYLOAD_LIMIT = 8000;

    private final JdbcTemplate jdbc;
    private final CombinedCatalogStore store;
    private final ObjectMapper json;
    private final CatalogProposalReview review;

    public CatalogChangeRequests(JdbcTemplate jdbc, CombinedCatalogStore store, ObjectMapper json,
                                 CatalogProposalReview review) {
        this.jdbc = jdbc;
        this.store = store;
        this.json = json;
        this.review = review;
    }

    /** 제안 접수. 원본은 건드리지 않는다. 데이터셋 이름은 여기서 검증해 잘못된 제안이 쌓이지 않게 한다. */
    public Map<String, Object> propose(long proposerId, CatalogAuditLog.Action action, String dataset,
                                       String key, Map<String, String> payload, String reason) {
        if (!CombinedCatalogCsv.DATASETS.contains(dataset))
            throw ApiException.planNotFound("알 수 없는 데이터셋: " + dataset);
        if (action != CatalogAuditLog.Action.DELETE && (payload == null || payload.isEmpty()))
            throw ApiException.requiredMissing("row", "변경할 필드를 보내주세요.");
        String body = payload == null ? null : write(payload);
        if (body != null && body.length() > PAYLOAD_LIMIT)
            throw ApiException.requiredMissing("row", "변경 내용이 너무 큽니다.");

        // 제안 내용을 스마트초이스·AI 두 소스로 대조한다(D-29). 판정이 승인 가능 여부를 정한다.
        CatalogProposalReview.Result verdict = review.review(action, dataset, payload);

        Long id = jdbc.queryForObject("""
                INSERT INTO catalog_change_request
                    (proposer_id, action, dataset, row_key, payload, reason, status,
                     review_status, review_detail, reviewed_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, now()) RETURNING id""",
                Long.class, proposerId, action.name(), dataset, key, body, reason,
                verdict.status(), verdict.detail());
        log.info("카탈로그 변경 제안 #{} {} {} {} proposer={} 검토={}",
                id, action, dataset, key, proposerId, verdict.status());
        return Map.of("requestId", id, "status", "PENDING",
                "reviewStatus", verdict.status(), "reviewDetail", verdict.detail());
    }

    /** 승인 → 실제 반영. 반영 실패는 제안을 FAILED 로 닫고 예외를 올린다(원본은 store 가 되돌린다). */
    public Map<String, Object> approve(long approverId, long requestId) {
        Map<String, Object> request = claim(requestId);
        // 두 소스 중 하나라도 "다른 금액"을 말했으면 승인하지 않는다(D-29).
        // 둘 다 확인 못 한 UNVERIFIED 는 막지 않는다 — 그건 사용자 제보로 잡는다(D-18).
        if ("MISMATCH".equals(request.get("review_status")))
            throw ApiException.conflict("검토에서 금액 불일치가 확인된 제안입니다: " + request.get("review_detail"));
        var action = CatalogAuditLog.Action.valueOf((String) request.get("action"));
        String dataset = (String) request.get("dataset");
        String key = (String) request.get("row_key");
        long proposerId = ((Number) request.get("proposer_id")).longValue();
        // 감사 기록에 "누가 제안하고 누가 승인했는지"를 남긴다. 실제 반영자는 승인자다.
        String trace = "요청#" + requestId + " 제안자=" + proposerId + " 승인자=" + approverId;

        try {
            switch (action) {
                case CREATE -> store.create(dataset, read(request), approverId, trace);
                case UPDATE -> store.update(dataset, key, read(request), approverId, trace);
                case DELETE -> store.delete(dataset, key, approverId, trace);
            }
        } catch (RuntimeException e) {
            close(requestId, approverId, "FAILED", e.getMessage());
            throw e;
        }
        close(requestId, approverId, "APPROVED", null);
        updateCandidate(request, "VERIFIED");
        log.info("카탈로그 변경 승인 #{} {} {} {} approver={}", requestId, action, dataset, key, approverId);
        return Map.of("requestId", requestId, "status", "APPROVED");
    }

    public Map<String, Object> reject(long approverId, long requestId, String note) {
        Map<String, Object> request = claim(requestId);
        close(requestId, approverId, "REJECTED", note);
        updateCandidate(request, "REJECTED");
        log.info("카탈로그 변경 거절 #{} approver={}", requestId, approverId);
        return Map.of("requestId", requestId, "status", "REJECTED");
    }

    public List<Map<String, Object>> list(String status, int limit) {
        if (status == null || status.isBlank())
            return jdbc.queryForList("SELECT * FROM catalog_change_request ORDER BY id DESC LIMIT ?", limit);
        return jdbc.queryForList(
                "SELECT * FROM catalog_change_request WHERE status = ? ORDER BY id DESC LIMIT ?", status, limit);
    }

    /**
     * PENDING 인 제안을 잡는다. 이미 처리됐으면 409 — 같은 제안이 두 번 반영되면 안 된다.
     * {@code FOR UPDATE} 로 동시 승인 두 건이 같은 제안을 함께 통과하지 못하게 막는다.
     */
    private Map<String, Object> claim(long requestId) {
        var rows = jdbc.queryForList("SELECT * FROM catalog_change_request WHERE id = ? FOR UPDATE", requestId);
        if (rows.isEmpty()) throw ApiException.planNotFound("변경 제안을 찾을 수 없습니다: " + requestId);
        Map<String, Object> request = rows.get(0);
        if (!"PENDING".equals(request.get("status")))
            throw ApiException.conflict("이미 처리된 제안입니다: " + request.get("status"));
        return request;
    }

    private void close(long requestId, long approverId, String status, String note) {
        jdbc.update("""
                UPDATE catalog_change_request
                SET status = ?, decided_by = ?, decided_at = now(), decision_note = ?
                WHERE id = ? AND status = 'PENDING'""",
                status, approverId, cut(note), requestId);
    }

    /** 결손에서 시작한 요금제 CREATE 제안만 같은 상태로 닫는다. */
    private void updateCandidate(Map<String, Object> request, String status) {
        if (!"CREATE".equals(request.get("action")) || !"mobile_plan".equals(request.get("dataset"))) return;
        String key = (String) request.get("row_key");
        int separator = key == null ? -1 : key.indexOf('|');
        if (separator < 1) return;
        try {
            jdbc.update("""
                    UPDATE catalog_candidate SET status = ?, updated_at = now()
                    WHERE kind = 'MOBILE_PLAN' AND query_text = ?
                    """, status, key.substring(0, separator) + " " + key.substring(separator + 1));
        } catch (RuntimeException e) {
            log.warn("결손 상태 갱신 실패 — 카탈로그 처리는 유지: {} ({})", key, e.getClass().getSimpleName());
        }
    }

    private Map<String, String> read(Map<String, Object> request) {
        String payload = (String) request.get("payload");
        if (payload == null) throw ApiException.requiredMissing("row", "제안에 변경 내용이 없습니다.");
        try {
            return json.readValue(payload, new TypeReference<Map<String, String>>() { });
        } catch (Exception e) {
            throw ApiException.requiredMissing("row", "제안 내용을 읽을 수 없습니다.");
        }
    }

    private String write(Map<String, String> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            throw ApiException.requiredMissing("row", "변경 내용을 저장할 수 없습니다.");
        }
    }

    private static String cut(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
