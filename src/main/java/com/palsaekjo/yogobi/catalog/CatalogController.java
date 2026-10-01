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
    private final Duration memo;
    private volatile Memo<List<ServiceView>> services;
    private volatile Memo<List<PlanView>> plans;

    /**
     * 서버 쪽 짧은 기억(G-89 f). 브라우저 캐시는 공격자에게 의미가 없다 — 공개·무제한 경로에서 요청마다
     * 1,700행 조인을 다시 해, 동시 수백 건이면 작은 머신과 커넥션 10개가 찼다(이 경로가 상태 검사이기도 하다).
     * 카탈로그는 하루에 많아야 한 번 바뀌므로 60초 늦게 보여도 된다.
     */
    public CatalogController(CatalogReader reader,
                             @org.springframework.beans.factory.annotation.Value("${yogobi.catalog.memo-seconds:60}") long memoSeconds) {
        this.reader = reader;
        this.memo = Duration.ofSeconds(memoSeconds);
    }

    private record Memo<T>(T value, java.time.Instant at) {
        boolean fresh(Duration ttl) {
            return at.plus(ttl).isAfter(java.time.Instant.now());
        }
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
        var current = services;
        if (current == null || !current.fresh(memo)) {
            // 만료 순간 몰린 요청이 다 같이 DB 를 읽지 않게 한 번에 하나만 갱신한다(풀이 5개다, G-91).
            synchronized (this) {
                current = services;
                if (current == null || !current.fresh(memo))
                    services = current = new Memo<>(reader.listServices(), java.time.Instant.now());
            }
        }
        return ResponseEntity.ok().cacheControl(CACHE).body(ApiResponse.ok(current.value()));
    }

    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<List<PlanView>>> plans() {
        var current = plans;
        if (current == null || !current.fresh(memo)) {
            synchronized (this) {
                current = plans;
                if (current == null || !current.fresh(memo))
                    plans = current = new Memo<>(reader.listPlans(), java.time.Instant.now());
            }
        }
        return ResponseEntity.ok().cacheControl(CACHE).body(ApiResponse.ok(current.value()));
    }

    @GetMapping("/plans/{id}/benefits")
    public ApiResponse<List<BenefitView>> benefits(@PathVariable long id) {
        return ApiResponse.ok(reader.listBenefits(id)
                .orElseThrow(() -> ApiException.planNotFound("선택한 요금제를 찾지 못했어요. 요금제를 다시 골라 주세요.")));
    }
}
