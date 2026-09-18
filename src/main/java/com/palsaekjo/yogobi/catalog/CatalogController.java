package com.palsaekjo.yogobi.catalog;

import com.palsaekjo.yogobi.catalog.CatalogReader.BenefitView;
import com.palsaekjo.yogobi.catalog.CatalogReader.PlanView;
import com.palsaekjo.yogobi.catalog.CatalogReader.ServiceView;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 카탈로그 마스터 읽기 전용 API (docs/architecture.md §3 MVP). */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {
    private final CatalogReader reader;

    public CatalogController(CatalogReader reader) {
        this.reader = reader;
    }

    /**
     * 카탈로그는 공개·읽기 전용이고 하루에 많아야 한 번 바뀐다. 요금제 목록은 1,700행(약 370KB)이라
     * 화면이 검색할 때마다 다시 받으면 그 시간이 그대로 "검색이 안 되는 것처럼" 보인다(사용자 피드백 2026-09-18).
     * 5분 캐시를 붙인다 — Spring Security 의 기본 {@code no-store} 는 헤더가 이미 있으면 덮지 않는다.
     * 회원별 값이 아니고 쿠키도 안 받는 경로라 {@code public} 이 안전하다.
     */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    @GetMapping("/services")
    public ResponseEntity<ApiResponse<List<ServiceView>>> services() {
        return ResponseEntity.ok().cacheControl(CACHE).body(ApiResponse.ok(reader.listServices()));
    }

    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<List<PlanView>>> plans() {
        return ResponseEntity.ok().cacheControl(CACHE).body(ApiResponse.ok(reader.listPlans()));
    }

    @GetMapping("/plans/{id}/benefits")
    public ApiResponse<List<BenefitView>> benefits(@PathVariable long id) {
        return ApiResponse.ok(reader.listBenefits(id)
                .orElseThrow(() -> ApiException.planNotFound("요금제를 찾을 수 없습니다: " + id)));
    }
}
