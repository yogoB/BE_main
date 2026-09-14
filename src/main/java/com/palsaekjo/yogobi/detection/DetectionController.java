package com.palsaekjo.yogobi.detection;

import com.palsaekjo.yogobi.common.ApiResponse;
import com.palsaekjo.yogobi.detection.domain.DetectionFinding;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회원 본인의 중복 결제 탐지 결과. 인증 필요(ROLE_MEMBER). 요청 시 현재 구독·요금제 기준으로 재탐지해 저장·반환한다. */
@RestController
@RequestMapping("/api/v1/me")
public class DetectionController {
    private final DetectionService detection;

    public DetectionController(DetectionService detection) {
        this.detection = detection;
    }

    @GetMapping("/detections")
    public ApiResponse<List<DetectionFinding>> detections(Principal principal) {
        return ApiResponse.ok(detection.detectForUser(Long.parseLong(principal.getName())));
    }
}
