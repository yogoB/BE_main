package com.palsaekjo.yogobi.subscription;

import com.palsaekjo.yogobi.common.ApiResponse;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회원 본인의 구독·현재 요금제 API. 인증 필요(ROLE_MEMBER), 변경은 CSRF. userId는 인증 주체에서만 취한다. */
@RestController
@RequestMapping("/api/v1/me")
public class MeSubscriptionController {
    private final UserSubscriptionService subscriptions;

    public MeSubscriptionController(UserSubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    public record AddSubscription(Long tierId, Long monthlyPrice) {
    }

    public record CurrentPlan(Long planId) {
    }

    @GetMapping("/subscriptions")
    public ApiResponse<List<UserSubscriptionService.View>> list(Principal principal) {
        return ApiResponse.ok(subscriptions.list(userId(principal)));
    }

    @PostMapping("/subscriptions")
    public ApiResponse<UserSubscriptionService.View> add(@RequestBody AddSubscription body, Principal principal) {
        return ApiResponse.ok(subscriptions.add(userId(principal), body.tierId(), body.monthlyPrice()));
    }

    @DeleteMapping("/subscriptions/{id}")
    public ApiResponse<Map<String, Boolean>> remove(@PathVariable long id, Principal principal) {
        subscriptions.remove(userId(principal), id);
        return ApiResponse.ok(Map.of("removed", true));
    }

    @PostMapping("/current-plan")
    public ApiResponse<Map<String, Boolean>> currentPlan(@RequestBody CurrentPlan body, Principal principal) {
        subscriptions.setCurrentPlan(userId(principal), body.planId());
        return ApiResponse.ok(Map.of("updated", true));
    }

    private static long userId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}
