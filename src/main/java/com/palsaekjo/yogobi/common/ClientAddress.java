package com.palsaekjo.yogobi.common;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 요청의 발신지. 프론트 nginx 가 Fly 엣지에서 받은 진짜 클라이언트 IP 를 {@code X-Client-IP} 로 넘긴다
 * (nginx 가 항상 덮어쓰므로 브라우저 위조분은 거기서 잘린다 — H-1, 2026-09-18 런타임 확인).
 *
 * <p><b>그 헤더는 nginx 의 비밀 헤더({@link #EDGE_HEADER})가 함께 올 때만 믿는다</b>(G-90 a). BE 가 공개 주소로도
 * 열려 있어, 직접 호출자가 요청마다 다른 {@code X-Client-IP} 를 지어내면 매번 새 한도를 받았다 — 공개 계산 경로의
 * 한도가 사실상 없었다. 믿지 않으면 TCP 발신지를 쓴다. 전달 헤더 처리(native)를 켠 뒤로는 Fly 가 붙인 실제 주소다.
 *
 * <p>{@code X-Forwarded-For} 는 직접 보지 않는다 — 누적 헤더라 첫 값을 사용자가 정한다. Tomcat 이 신뢰하는
 * 프록시 기준으로 맨 오른쪽 값만 발신지로 바꿔 준다.
 */
public final class ClientAddress {
    /** 프론트 nginx 가 붙이는 공유 비밀 헤더. 값은 양쪽 앱의 {@code EDGE_SHARED_SECRET}. */
    public static final String EDGE_HEADER = "X-Edge-Auth";

    private static volatile byte[] edgeSecret = new byte[0];

    private ClientAddress() { }

    /** 기동 시 한 번 설정한다({@link EdgeSecret}). 비어 있으면 예전처럼 {@code X-Client-IP} 를 믿는다. */
    static void trustEdgeSecret(String secret) {
        edgeSecret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    public static String of(HttpServletRequest req) {
        return forwarded(req) ? req.getHeader("X-Client-IP").strip() : req.getRemoteAddr();
    }

    /** 믿을 수 있는 {@code X-Client-IP} 가 왔는지 — 레이트 리밋 로그용. */
    public static boolean forwarded(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Client-IP");
        return forwarded != null && !forwarded.isBlank() && forwarded.length() <= 64 && fromEdge(req);
    }

    private static boolean fromEdge(HttpServletRequest req) {
        byte[] secret = edgeSecret;
        if (secret.length == 0) return true;
        String presented = req.getHeader(EDGE_HEADER);
        return presented != null && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), secret);
    }
}
