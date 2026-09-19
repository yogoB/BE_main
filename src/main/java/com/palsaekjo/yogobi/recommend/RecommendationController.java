package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.common.ApiResponse;
import com.palsaekjo.yogobi.common.FunnelCounter;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 무상태 추천 — 필터 경로가 부르는 유일한 추천 엔드포인트 (docs/domain.md 원칙 3). */
@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {
    private final RecommendationService service;
    private final Narrator narrator;
    private final FunnelCounter funnel;
    private final RecommendationStats stats;
    private final MemberSavings memberSavings;

    public RecommendationController(RecommendationService service, Narrator narrator, FunnelCounter funnel,
                                    RecommendationStats stats, MemberSavings memberSavings) {
        this.service = service;
        this.narrator = narrator;
        this.funnel = funnel;
        this.stats = stats;
        this.memberSavings = memberSavings;
    }

    /**
     * 계산만 한다 — 내레이터를 부르지 않는다(D-50, 2026-09-18). {@code message}·{@code reasons}·{@code notices} 는
     * 비어 나가고 {@link #narrate} 가 따로 준다. 하루에 설명 경로 사고가 넷이었고 그때마다 결과 화면이
     * 내레이터 지연·장애를 같이 맞았다. 결과는 결과대로, 설명은 사용자가 펼칠 때.
     *
     * <p>{@code principal} 은 비회원이면 null 이다 — 이 경로는 공개다. 결과를 돌려주는 시점이
     * 곧 "회원은 리포트를 봤고, 비회원은 로그인 게이트를 만났다"는 뜻이라 여기서 센다(D-36).
     *
     * <p>같은 이유로 절감액 표본도 여기서 남긴다(D-59). 기준은 <b>로그인한 채 결과를 본 회원</b>이고,
     * 지금 요금제를 알려줘서 {@code current} 가 있을 때만이다 — 그때만 "지금보다 얼마나 싼가"가 있다.
     */
    @PostMapping
    public ApiResponse<RecommendationResponse> recommend(@RequestBody RecommendationRequest request,
                                                        Principal principal, HttpServletRequest http) {
        RecommendationResponse result = service.recommend(request);
        funnel.record(principal == null ? FunnelCounter.GATE_SHOWN : FunnelCounter.REPORT_SHOWN,
                FunnelCounter.actor(principal, http));
        if (!result.results().isEmpty())
            stats.record(result.results().get(0).planId(), request.required().monthlyDataGb());
        if (principal != null && result.current() != null)
            memberSavings.record(Long.parseLong(principal.getName()), result.current().monthlySavings());
        return ApiResponse.ok(result);
    }

    /**
     * 같은 요청 본문으로 1순위 설명만 만든다. 추천을 다시 계산한다 — 결과를 저장하지 않는 무상태 경로라
     * 그게 가장 단순하고, 계산은 싸다(내레이터 왕복이 더 비싸다). 후보가 없으면 빈 설명이다.
     * 퍼널은 세지 않는다 — 리포트를 본 것은 위에서 이미 셌다.
     */
    @PostMapping("/narrate")
    public ApiResponse<Narrator.Narration> narrate(@RequestBody RecommendationRequest request) {
        RecommendationResponse result = service.recommend(request);
        if (result.results().isEmpty()) return ApiResponse.ok(Narrator.Narration.none());
        return ApiResponse.ok(narrator.narrationFor(
                result.results().get(0), result.missingInputs(), result.candidateCount(), result.current()));
    }
}
