package com.palsaekjo.yogobi.catalog;

import com.palsaekjo.yogobi.catalog.CatalogCandidateRecorder.Kind;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import com.palsaekjo.yogobi.common.ClientAddress;
import com.palsaekjo.yogobi.user.AuthRateLimit;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면에서 찾지 못한 것을 <b>결손</b>으로 남긴다(D-56). 공개 경로다 — 추천과 같은 취급이다.
 *
 * <p>지금까지 "이 요금제가 목록에 없어요" 는 일반 제보로 갔다. 그건 제보가 아니라 <b>수집 목록</b>이다.
 * 여기로 들어오면 {@code catalog_candidate} 의 {@code requested_cnt} 가 올라 백오피스 결손 보드 위로 온다
 * (통신사는 추천 경로가 이미 자동으로 남긴다 — {@code RecommendationService.recordUnknownCarrier}).
 *
 * <p><b>응답은 언제나 같다.</b> 그 이름이 카탈로그에 있는지 없는지 알려주지 않는다 —
 * 공개 경로가 카탈로그를 훑는 통로가 되면 안 된다. 기록 실패도 사용자에게 드러내지 않는다(fail-soft).
 */
@RestController
@RequestMapping("/api/v1/catalog/gaps")
public class CatalogGapController {
    /** 발신지당 15분 창. 공개 쓰기 경로라 상한이 필요하다 — 표(10,000행)를 쓰레기로 채우지 못하게. */
    private final int limit;
    private final CatalogCandidateRecorder recorder;
    private final AuthRateLimit limits;

    public CatalogGapController(CatalogCandidateRecorder recorder, AuthRateLimit limits,
                                @Value("${yogobi.catalog.gap-limit:60}") int limit) {
        this.recorder = recorder;
        this.limits = limits;
        this.limit = limit;
    }

    public record Gap(String kind, String queryText) { }

    @PostMapping
    public ApiResponse<Map<String, Boolean>> record(@RequestBody Gap gap, HttpServletRequest http) {
        limits.check("gap:" + ClientAddress.of(http), limit);
        String text = gap.queryText() == null ? "" : gap.queryText().strip();
        if (text.isEmpty() || text.length() > 200) {
            throw ApiException.requiredMissing("queryText", "찾으시던 이름을 1~200자로 알려주세요.");
        }
        Kind kind;
        try {
            kind = Kind.valueOf(gap.kind() == null ? "" : gap.kind());
        } catch (IllegalArgumentException e) {
            throw ApiException.requiredMissing("kind", "종류를 확인해 주세요.");
        }
        recorder.record(kind, text);
        return ApiResponse.ok(Map.of("recorded", true));
    }
}
