package com.palsaekjo.yogobi.detection;

import com.palsaekjo.yogobi.catalog.CatalogReader;
import com.palsaekjo.yogobi.common.ApiResponse;
import com.palsaekjo.yogobi.detection.domain.DetectionFinding;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회원 본인의 중복 결제 탐지 결과. 인증 필요(ROLE_MEMBER). 요청 시 현재 구독·요금제 기준으로 재탐지해 저장·반환한다. */
@RestController
@RequestMapping("/api/v1/me")
public class DetectionController {
    private final DetectionService detection;
    private final CatalogReader catalog;
    private final ObjectProvider<DetectionNarrator> narrator;

    public DetectionController(DetectionService detection, CatalogReader catalog,
                               ObjectProvider<DetectionNarrator> narrator) {
        this.detection = detection;
        this.catalog = catalog;
        this.narrator = narrator;
    }

    /** 탐지 결과와 그 설명. 문구는 내레이터가 만든다(D-46) — 화면이 규칙 문구를 들고 있지 않는다. */
    public record DetectionView(List<DetectionFinding> findings,
                                List<DetectionNarrator.Explained> lines, String summary) {
    }

    @GetMapping("/detections")
    public ApiResponse<DetectionView> detections(Principal principal) {
        List<DetectionFinding> findings = detection.detectForUser(Long.parseLong(principal.getName()));
        List<String> names = findings.stream().map(this::targetName).toList();
        DetectionNarrator port = narrator.getIfAvailable();
        // 내레이터가 아예 없어도 금액과 대상은 우리가 아는 값이다. 문구만 없이 보여준다.
        var explained = port == null ? DetectionNarrator.fallback(findings, names) : port.explain(findings, names);
        return ApiResponse.ok(new DetectionView(findings, explained.lines(), explained.summary()));
    }

    /** `service:{id}` · `bundle:{id}` 를 사람이 읽는 이름으로. 모르는 참조는 그대로 둔다. */
    private String targetName(DetectionFinding finding) {
        String reference = finding.targetRef() == null ? "" : finding.targetRef();
        String[] parts = reference.split(":", 2);
        if (parts.length == 2 && "service".equals(parts[0])) {
            Map<String, String> names = catalog.listServices().stream()
                    .collect(Collectors.toMap(service -> String.valueOf(service.id()),
                            CatalogReader.ServiceView::name, (a, b) -> a));
            return names.getOrDefault(parts[1], "서비스 #" + parts[1]);
        }
        return parts.length == 2 && "bundle".equals(parts[0]) ? "묶음 상품" : reference;
    }
}
