package com.palsaekjo.yogobi.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 개인정보 처리방침(공개) 조회와 회원의 동의 조회·변경. 회원 데이터 삭제(탈퇴)는 AuthController DELETE /me. */
@RestController
@RequestMapping("/api/v1")
public class PrivacyController {
    private final ConsentService consent;

    public PrivacyController(ConsentService consent) {
        this.consent = consent;
    }

    @GetMapping("/privacy-policy")
    public ApiResponse<PrivacyPolicy.Policy> policy() {
        return ApiResponse.ok(PrivacyPolicy.current());
    }

    @GetMapping("/me/consent")
    public ApiResponse<List<ConsentService.Consent>> myConsent(Principal principal) {
        return ApiResponse.ok(consent.view(Long.parseLong(principal.getName())));
    }

    /**
     * 바뀐 처리방침을 확인했다고 기록한다(필수 항목만 현재 버전으로). 마케팅 동의는 승계하지 않는다 —
     * "방침을 읽었다"가 "광고를 받겠다"를 뜻하지 않는다.
     */
    @PostMapping("/me/consent/acknowledge")
    public ApiResponse<Map<String, String>> acknowledge(Principal principal) {
        return ApiResponse.ok(Map.of("acknowledgedVersion",
                consent.acknowledge(Long.parseLong(principal.getName()))));
    }

    @PostMapping("/me/consent/marketing")
    public ApiResponse<Map<String, Boolean>> marketing(@RequestBody JsonNode body, Principal principal) {
        boolean agree = requireAgree(body);
        consent.setMarketing(Long.parseLong(principal.getName()), agree);
        return ApiResponse.ok(Map.of("agreed", agree));
    }

    /**
     * 절감 추천 알림 동의를 켜고 끈다(2026-09-21). 마케팅과 <b>같은 모양</b>이다 — 같은 성격의
     * 선택 항목을 다른 모양으로 두면 화면이 둘을 다르게 다루게 된다.
     *
     * <p>로그인할 때 체크만 받고 <b>끌 방법이 없었다.</b> 방침 7조가 처리정지를 약속하는데 길이
     * 없으면 빈말이다. 발송 기능은 아직 없지만 끄는 길을 먼저 연다 — 보내기 시작한 뒤에 만들면
     * 그 사이에 받은 사람은 끌 수 없었다.
     */
    @PostMapping("/me/consent/savings-alert")
    public ApiResponse<Map<String, Boolean>> savingsAlert(@RequestBody JsonNode body, Principal principal) {
        boolean agree = requireAgree(body);
        consent.setSavingsAlert(Long.parseLong(principal.getName()), agree);
        return ApiResponse.ok(Map.of("agreed", agree));
    }

    /** {@code agree} 는 boolean 이어야 한다. 빠지거나 문자열이면 400 — 기본값을 추측하지 않는다. */
    private static boolean requireAgree(JsonNode body) {
        if (!body.path("agree").isBoolean()) {
            throw ApiException.requiredMissing("agree", "동의 여부를 boolean(agree)으로 보내 주세요.");
        }
        return body.get("agree").booleanValue();
    }
}
