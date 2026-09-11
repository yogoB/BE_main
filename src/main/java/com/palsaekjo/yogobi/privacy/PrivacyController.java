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

    @PostMapping("/me/consent/marketing")
    public ApiResponse<Map<String, Boolean>> marketing(@RequestBody JsonNode body, Principal principal) {
        if (!body.path("agree").isBoolean()) {
            throw ApiException.requiredMissing("agree", "동의 여부를 boolean(agree)으로 보내 주세요.");
        }
        boolean agree = body.get("agree").booleanValue();
        consent.setMarketing(Long.parseLong(principal.getName()), agree);
        return ApiResponse.ok(Map.of("agreed", agree));
    }
}
