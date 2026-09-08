package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 특정 조합 총비용 (docs/architecture.md §3 MVP). 추천과 같은 계산 엔진을 쓴다. */
@RestController
@RequestMapping("/api/v1/calculator")
public class CalculatorController {
    private final RecommendationService service;

    public CalculatorController(RecommendationService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<CalculatorResponse> calculate(@RequestBody CalculatorRequest request) {
        return ApiResponse.ok(service.calculate(request));
    }
}
