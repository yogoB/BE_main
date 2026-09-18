package com.palsaekjo.yogobi.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청의 발신지. 프론트 nginx 가 Fly 엣지에서 받은 진짜 클라이언트 IP 를 {@code X-Client-IP} 로 넘긴다
 * (nginx 가 항상 덮어쓰므로 브라우저 위조분은 거기서 잘린다 — H-1, 2026-09-18 런타임 확인).
 * 없으면 TCP peer 인데, 프록시 뒤에서는 전 사용자에게 같은 값이다.
 *
 * <p>{@code X-Forwarded-For} 는 보지 않는다 — 누적 헤더라 첫 값을 사용자가 정한다.
 * BE 가 공개 주소로도 열려 있어 직접 호출자는 이 헤더를 지어낼 수 있지만, 그러면 자기 몫만 쪼갤 뿐이다.
 */
public final class ClientAddress {
    private ClientAddress() { }

    public static String of(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Client-IP");
        boolean usable = forwarded != null && !forwarded.isBlank() && forwarded.length() <= 64;
        return usable ? forwarded.strip() : req.getRemoteAddr();
    }

    /** 헤더가 실제로 왔는지 — 레이트 리밋 로그용. */
    public static boolean forwarded(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Client-IP");
        return forwarded != null && !forwarded.isBlank() && forwarded.length() <= 64;
    }
}
