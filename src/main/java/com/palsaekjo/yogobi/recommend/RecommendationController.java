package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.common.ApiResponse;
import com.palsaekjo.yogobi.common.FunnelCounter;
import java.security.Principal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 무상태 추천 — 필터 경로와 챗봇 경로가 공유하는 유일한 엔드포인트 (docs/domain.md 원칙 3). */
@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {
    private final RecommendationService service;
    private final Narrator narrator;
    private final FunnelCounter funnel;

    public RecommendationController(RecommendationService service, Narrator narrator, FunnelCounter funnel) {
        this.service = service;
        this.narrator = narrator;
        this.funnel = funnel;
    }

    /**
     * {@code principal} 은 비회원이면 null 이다 — 이 경로는 공개다. 결과를 돌려주는 시점이
     * 곧 "회원은 리포트를 봤고, 비회원은 로그인 게이트를 만났다"는 뜻이라 여기서 센다(D-36).
     */
    @PostMapping
    public ApiResponse<RecommendationResponse> recommend(@RequestBody RecommendationRequest request,
                                                        Principal principal) {
        RecommendationResponse result = service.recommend(request);
        funnel.record(principal == null ? FunnelCounter.GATE_SHOWN : FunnelCounter.REPORT_SHOWN);
        if (result.results().isEmpty()) return ApiResponse.ok(result); // 카탈로그 결손(G-12): 사유 없음
        // 챗봇 경로와 같은 결과 카드를 공유하도록 1순위 설명을 붙인다(원칙 3·5-⑤).
        // message·reasons 둘 다 모델 키 없이 나온다. AI 장애면 둘 다 비고 금액은 그대로다.
        return ApiResponse.ok(result.withNarration(narrator.narrationFor(
                result.results().get(0), result.missingInputs(), result.candidateCount(),
                result.current() == null ? null : result.current().cost().monthlyTotal())));
    }
}
