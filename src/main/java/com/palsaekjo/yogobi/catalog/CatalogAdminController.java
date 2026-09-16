package com.palsaekjo.yogobi.catalog;

import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public CatalogAdminController(CombinedCatalogStore store) {
        this.store = store;
    }

    /** 데이터셋 목록과 행 수. */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> datasets() {
        return ApiResponse.ok(store.datasets().stream()
                .map(name -> Map.<String, Object>of("dataset", name, "rows", store.rows(name).size()))
                .toList());
    }

    @GetMapping("/{dataset}")
    public ApiResponse<List<Map<String, String>>> rows(@PathVariable String dataset) {
        return ApiResponse.ok(store.rows(dataset));
    }

    @PostMapping("/{dataset}")
    public ApiResponse<Map<String, String>> create(@PathVariable String dataset,
                                                   @RequestBody Map<String, String> row) {
        require(row);
        return ApiResponse.ok(store.create(dataset, row));
    }

    /** 부분 수정. `key`는 데이터셋의 키 컬럼 값을 '|'로 이은 값이다(예: `SKT|베스트 Max(T 우주)`). */
    @PatchMapping("/{dataset}/{key}")
    public ApiResponse<Map<String, String>> update(@PathVariable String dataset, @PathVariable String key,
                                                   @RequestBody Map<String, String> row) {
        require(row);
        return ApiResponse.ok(store.update(dataset, key, row));
    }

    @DeleteMapping("/{dataset}/{key}")
    public ApiResponse<Map<String, String>> delete(@PathVariable String dataset, @PathVariable String key) {
        store.delete(dataset, key);
        return ApiResponse.ok(Map.of("deleted", key));
    }

    private static void require(Map<String, String> row) {
        if (row == null || row.isEmpty()) throw ApiException.requiredMissing("row", "변경할 필드를 보내주세요.");
    }
}
