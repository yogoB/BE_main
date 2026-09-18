package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.common.ApiResponse;
import java.security.Principal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 본인의 변경 시점(회수기간) 조회. 인증 필요(ROLE_MEMBER), 읽기 전용(상태 변경 없음).
 * 전환비용·약정잔여는 사용자 추정치(모르면 0). 현재 요금제는 {@code currentPlanId} 로 넘기거나(이번 흐름의 입력),
 * 없으면 {@code POST /me/current-plan} 으로 저장해 둔 값을 쓴다(G-35). 넘긴 값은 프로필을 덮어쓰지 않는다.
 */
@RestController
@RequestMapping("/api/v1/me")
public class SwitchTimingController {
    private final SwitchTimingService switchTiming;
    private final ObjectProvider<SwitchTimingNarrator> narrator;
    private final com.palsaekjo.yogobi.common.FunnelCounter funnel;

    public SwitchTimingController(SwitchTimingService switchTiming,
                                  ObjectProvider<SwitchTimingNarrator> narrator,
                                  com.palsaekjo.yogobi.common.FunnelCounter funnel) {
        this.switchTiming = switchTiming;
        this.narrator = narrator;
        this.funnel = funnel;
    }

    /** 판정과 그 설명. 문구는 내레이터가 만든다(D-47) — 화면이 판정별 문장을 들고 있지 않는다. */
    public record TimingView(SwitchTimingService.Response timing, String headline, String note) {
    }

    @GetMapping("/switch-timing")
    public ApiResponse<TimingView> switchTiming(
            @RequestParam long targetPlanId,
            @RequestParam(defaultValue = "0") long switchingCost,
            @RequestParam(defaultValue = "0") int remainingContractMonths,
            @RequestParam(required = false) Long currentPlanId,
            // 사용자가 화면에 적은 약정 만료일. 서버는 이 날짜를 모르므로 받아서 문구에만 쓴다.
            @RequestParam(required = false) String expiryDate,
            Principal principal) {
        long userId = Long.parseLong(principal.getName());
        SwitchTimingService.Response timing = switchTiming.evaluate(
                userId, targetPlanId, switchingCost, remainingContractMonths, currentPlanId);
        funnel.record(com.palsaekjo.yogobi.common.FunnelCounter.CALENDAR_SHOWN,
                com.palsaekjo.yogobi.common.FunnelCounter.actor(userId));   // 퍼널 4단계(D-52)
        SwitchTimingNarrator port = narrator.getIfAvailable();
        var wording = port == null ? SwitchTimingNarrator.fallback(timing.status())
                : port.explain(timing, expiryDate);
        return ApiResponse.ok(new TimingView(timing, wording.headline(), wording.note()));
    }
}
