package com.palsaekjo.yogobi.catalog;

import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카탈로그 원본(합본 CSV) CRUD. D-21. 쓰기는 원본 파일을 고치고 DB에 재적재한다.
 *
 * <p>읽기 전용 공개 API(`/api/v1/catalog/**`)와 경로를 분리한다 — 그쪽은 permitAll이므로
 * 같은 경로에 쓰기를 두면 누구나 카탈로그를 고칠 수 있다. 이 경로는 인증을 요구한다(SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/admin/catalog")
public class CatalogAdminController {
    private final CombinedCatalogStore store;
    private final CatalogAuditLog audit;
    private final CatalogChangeRequests requests;

    public CatalogAdminController(CombinedCatalogStore store, CatalogAuditLog audit,
                                  CatalogChangeRequests requests) {
        this.store = store;
        this.audit = audit;
        this.requests = requests;
    }

    /** 데이터셋 목록과 행 수. */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> datasets() {
        return ApiResponse.ok(store.datasets().stream()
                .map(name -> Map.<String, Object>of("dataset", name, "rows", store.rows(name).size()))
                .toList());
    }

    /** 변경 이력(최신순). 원본이 파일이라 git 이력이 없으므로 이 목록이 유일한 감사 자료다(D-26). */
    @GetMapping("/audit")
    public ApiResponse<List<Map<String, Object>>> audit(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(this.audit.recent(Math.clamp(limit, 1, 500)));
    }

    @GetMapping("/{dataset}")
    public ApiResponse<List<Map<String, String>>> rows(@PathVariable String dataset) {
        return ApiResponse.ok(store.rows(dataset));
    }

    /* ---- 쓰기는 즉시 반영되지 않는다. 제안으로 접수되고 승인해야 반영된다(D-28). ---- */

    /** 행 추가 제안. 원본은 그대로다 — 승인 전까지 아무것도 바뀌지 않는다. */
    @PostMapping("/{dataset}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, Object>> proposeCreate(@PathVariable String dataset,
                                                          @RequestBody Map<String, String> row, Principal principal) {
        String reason = reason(row);
        require(row);
        return ApiResponse.ok(requests.propose(actor(principal), CatalogAuditLog.Action.CREATE,
                dataset, null, row, reason));
    }

    /** 부분 수정 제안. `key`는 데이터셋의 키 컬럼 값을 '|'로 이은 값이다(예: `SKT|베스트 Max(T 우주)`). */
    @PatchMapping("/{dataset}/{key}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, Object>> proposeUpdate(@PathVariable String dataset, @PathVariable String key,
                                                          @RequestBody Map<String, String> row, Principal principal) {
        String reason = reason(row);
        require(row);
        return ApiResponse.ok(requests.propose(actor(principal), CatalogAuditLog.Action.UPDATE,
                dataset, key, row, reason));
    }

    @DeleteMapping("/{dataset}/{key}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, Object>> proposeDelete(@PathVariable String dataset, @PathVariable String key,
                                                          Principal principal) {
        return ApiResponse.ok(requests.propose(actor(principal), CatalogAuditLog.Action.DELETE,
                dataset, key, null, null));
    }

    /** 대기 중인 제안 목록. `?status=PENDING|APPROVED|REJECTED|FAILED` 로 좁힌다. */
    @GetMapping("/requests")
    public ApiResponse<List<Map<String, Object>>> listRequests(@RequestParam(required = false) String status,
                                                               @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(requests.list(status, Math.clamp(limit, 1, 500)));
    }

    /** 승인 → 이때 실제로 파일·DB에 반영되고 감사 기록에 제안자·승인자가 함께 남는다. */
    @PostMapping("/requests/{id}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable long id, Principal principal) {
        return ApiResponse.ok(requests.approve(actor(principal), id));
    }

    @PostMapping("/requests/{id}/reject")
    public ApiResponse<Map<String, Object>> reject(@PathVariable long id,
                                                   @RequestBody(required = false) Map<String, String> body,
                                                   Principal principal) {
        return ApiResponse.ok(requests.reject(actor(principal), id, body == null ? null : body.get("reason")));
    }

    /** 제안 사유는 본문의 `reason` 으로 받되 변경 필드로는 쓰지 않는다. */
    private static String reason(Map<String, String> row) {
        return row.remove("reason");
    }

    /** 감사 기록에 남길 운영자. 이 경로는 ROLE_ADMIN 전용이라 principal 이 없을 수 없다. */
    private static long actor(Principal principal) {
        return Long.parseLong(principal.getName());
    }

    private static void require(Map<String, String> row) {
        if (row == null || row.isEmpty()) throw ApiException.requiredMissing("row", "변경할 필드를 보내주세요.");
    }
}
