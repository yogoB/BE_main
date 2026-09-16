package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.common.ApiResponse;
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

    public RecommendationController(RecommendationService service, Narrator narrator) {
        this.service = service;
        this.narrator = narrator;
    }

    @PostMapping
    public ApiResponse<RecommendationResponse> recommend(@RequestBody RecommendationRequest request) {
        RecommendationResponse result = service.recommend(request);
        if (result.results().isEmpty()) return ApiResponse.ok(result); // 카탈로그 결손(G-12): 사유 없음
        // 챗봇 경로와 같은 결과 카드를 공유하도록 1순위 사유를 붙인다(원칙 3·5-⑤). AI 장애면 빈 목록.
        return ApiResponse.ok(result.withReasons(
                narrator.reasonsFor(result.results().get(0), result.missingInputs())));
    }
}
