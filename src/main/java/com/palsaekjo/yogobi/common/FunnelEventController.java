package com.palsaekjo.yogobi.common;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면만 볼 수 있는 퍼널 단계를 받는 공개 경로. 보고서 §9.2 "결과 도달률"은 분모가 <b>입력 시작
 * 사용자</b>인데, 입력은 전부 브라우저 안에서 끝나 서버에 아무 요청도 오지 않는다 — 화면이 말해
 * 주지 않으면 영영 낼 수 없는 숫자다.
 *
 * <p><b>받는 종류는 {@link FunnelCounter#CLIENT_REPORTED} 뿐이다.</b> 서버가 스스로 관측하는
 * 단계(게이트·로그인·리포트·캘린더·저장)는 받지 않는다. 받게 하면 요청 한 번으로 리포트 조회 수를
 * 부풀릴 수 있고, 그러면 퍼널 전체가 증거로서 죽는다.
 *
 * <p>모르는 종류는 <b>거절하지 않고 조용히 버린다.</b> 화면이 새 이벤트를 먼저 배포해도 콘솔에
 * 빨간 줄이 뜨지 않아야 한다 — 지표 때문에 화면이 시끄러워지면 안 된다(D-52 와 같은 선).
 * 같은 사람은 하루에 종류당 한 번만 쌓인다({@code funnel_event} 의 기본키가 보장한다).
 */
@RestController
@RequestMapping("/api/v1/events")
public class FunnelEventController {
    private final FunnelCounter funnel;

    public FunnelEventController(FunnelCounter funnel) {
        this.funnel = funnel;
    }

    /** 본문은 {@code {"kind":"INPUT_STARTED"}}. 응답은 언제나 204 다 — 무엇을 받았는지 알려 주지 않는다. */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void record(@RequestBody(required = false) Map<String, Object> body,
                       Principal principal, HttpServletRequest http) {
        String kind = body == null ? null : String.valueOf(body.get("kind"));
        if (kind == null || !FunnelCounter.CLIENT_REPORTED.contains(kind)) return;
        funnel.record(kind, FunnelCounter.actor(principal, http));
    }
}
