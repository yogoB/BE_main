package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.common.FunnelCounter;
import com.palsaekjo.yogobi.common.ApiException;
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
    private final FunnelCounter funnel;

    public SwitchTimingController(SwitchTimingService switchTiming,
                                  ObjectProvider<SwitchTimingNarrator> narrator,
                                  FunnelCounter funnel) {
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
            // 모르면 null — 0 으로 읽으면 "전환비용이 없어 지금 바로 이득"이라고 단정하게 된다(G-78 f).
            @RequestParam(required = false) Long switchingCost,
            @RequestParam(defaultValue = "0") int remainingContractMonths,
            @RequestParam(required = false) Long currentPlanId,
            // 사용자가 화면에 적은 약정 만료일. 서버는 이 날짜를 모르므로 받아서 문구에만 쓴다.
            @RequestParam(required = false) String expiryDate,
            Principal principal) {
        // 범위 밖 입력은 문장에 그대로 찍히거나(20억 개월) 설명을 조용히 지웠다(형식 틀린 날짜) — G-78 g.
        if (remainingContractMonths < 0 || remainingContractMonths > 120)
            throw ApiException.requiredMissing("remainingContractMonths", "약정 남은 기간을 다시 확인해 주세요(0~120개월).");
        if (switchingCost != null && (switchingCost < 0 || switchingCost > 10_000_000))
            throw ApiException.requiredMissing("switchingCost", "위약금·전환비용을 원 단위로 다시 적어 주세요.");
        if (expiryDate != null) {
            try {
                java.time.LocalDate.parse(expiryDate);
            } catch (java.time.format.DateTimeParseException e) {
                throw ApiException.requiredMissing("expiryDate", "약정 만료일을 다시 골라 주세요.");
            }
        }
        long userId = Long.parseLong(principal.getName());
        SwitchTimingService.Response timing = switchTiming.evaluate(
                userId, targetPlanId, switchingCost == null ? 0 : switchingCost, remainingContractMonths, currentPlanId);
        funnel.record(FunnelCounter.CALENDAR_SHOWN,
                FunnelCounter.actor(userId));   // 퍼널 4단계(D-52)
        SwitchTimingNarrator port = narrator.getIfAvailable();
        // 약정이 남았는데 전환비용을 모르면 판정 문장을 쓰지 않는다 — 대신 무엇을 알려 주면 되는지 말한다(G-78 f).
        var wording = switchingCost == null && remainingContractMonths > 0
                ? new SwitchTimingNarrator.Wording("약정 해지 비용 확인 필요",
                        "약정이 " + remainingContractMonths + "개월 남았어요. 해지하면 위약금이 생길 수 있어 "
                                + "지금 바꿀지는 판단하지 않았어요. 위약금은 통신사 앱의 약정 정보에서 확인할 수 있어요.")
                : port == null ? SwitchTimingNarrator.fallback(timing)
                : port.explain(timing, expiryDate);
        return ApiResponse.ok(new TimingView(timing, wording.headline(), wording.note()));
    }
}
