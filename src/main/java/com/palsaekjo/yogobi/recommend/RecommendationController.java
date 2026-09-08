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

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<RecommendationResponse> recommend(@RequestBody RecommendationRequest request) {
        return ApiResponse.ok(service.recommend(request));
    }
}
