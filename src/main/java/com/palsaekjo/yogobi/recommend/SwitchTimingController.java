package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.common.ApiResponse;
import java.security.Principal;
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

    public SwitchTimingController(SwitchTimingService switchTiming) {
        this.switchTiming = switchTiming;
    }

    @GetMapping("/switch-timing")
    public ApiResponse<SwitchTimingService.Response> switchTiming(
            @RequestParam long targetPlanId,
            @RequestParam(defaultValue = "0") long switchingCost,
            @RequestParam(defaultValue = "0") int remainingContractMonths,
            @RequestParam(required = false) Long currentPlanId,
            Principal principal) {
        return ApiResponse.ok(switchTiming.evaluate(
                Long.parseLong(principal.getName()), targetPlanId, switchingCost, remainingContractMonths, currentPlanId));
    }
}
