package com.palsaekjo.yogobi.report;

import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import com.palsaekjo.yogobi.common.ClientAddress;
import com.palsaekjo.yogobi.user.AuthRateLimit;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품과 무관한 제보(D-41) — 화면·기능 오류(SYSTEM), 기타(OTHER). 접수만 한다.
 * 상품을 지정하는 제보는 그대로 {@code /api/v1/catalog/reports} 다.
 * 원문 IP 를 저장하지 않고 제보 URL 을 가져오지 않는다(catalog_report 와 같은 원칙).
 * <b>한 가지만 다르다</b>: 제보 1건마다 쿠폰 1장을 주므로 <b>로그인 상태면 회원 id 를 함께 남긴다</b>(V24).
 * 그래야 마이페이지 쿠폰함이 성립한다. 비로그인 제보는 그대로 익명이고 코드가 곧 소유 증명이다.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ServiceReportController {
    private static final Set<String> CATEGORIES = Set.of("SYSTEM", "OTHER");
    private final JdbcTemplate jdbc;
    private final AuthRateLimit limits;

    public ServiceReportController(JdbcTemplate jdbc, AuthRateLimit limits) {
        this.jdbc = jdbc;
        this.limits = limits;
    }

    /** pageUrl 은 프론트가 넘기는 우리 화면 경로("/results")다. 다른 사이트 주소는 받지 않는다. */
    public record Request(String category, String description, String pageUrl, String sourceUrl) { }
    public record Receipt(UUID id, String status, Coupon coupon) { }

    /**
     * 제보 1건 = 쿠폰 1장이라 <b>코드가 곧 제보 id</b> 다. 코드 칼럼을 따로 두지 않았다.
     * 오늘 이 쿠폰이 해제하는 것은 없다 — 요금 분석은 무료다(수익 모델은 D-01 로 범위 밖).
     * 리워드 표시와 향후 BM 훅으로 먼저 깔아 두는 것이다.
     */
    public record Coupon(UUID code, String status) { }

    @PostMapping
    public ApiResponse<Receipt> submit(@RequestBody Request body, HttpServletRequest request, Principal principal) {
        if (body.category() == null || !CATEGORIES.contains(body.category()))
            throw ApiException.requiredMissing("category", "제보 종류를 선택하세요.");
        if (body.description() == null || body.description().isBlank() || body.description().length() > 2000)
            throw ApiException.requiredMissing("description", "설명을 1~2000자로 입력하세요. 개인정보는 적지 마세요.");
        String page = blankToNull(body.pageUrl());
        if (page != null && (page.length() > 2000 || !page.startsWith("/") || page.startsWith("//")))
            throw ApiException.requiredMissing("pageUrl", "화면 경로는 /로 시작하는 우리 사이트 경로만 받습니다.");
        String source = blankToNull(body.sourceUrl());
        if (source != null) {
            try {
                URI uri = URI.create(source);
                if (source.length() > 2000 || !"https".equals(uri.getScheme()) || uri.getHost() == null
                        || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw ApiException.requiredMissing("sourceUrl", "인증정보·검색조건 없는 HTTPS 출처 링크를 입력하세요.");
            }
        }
        // 발신지는 ClientAddress 로 읽는다. getRemoteAddr() 은 프론트 nginx·Fly 프록시 뒤에서
        // <b>전 사용자에게 같은 값</b>이라, 15분에 5건이 서비스 전체의 상한이 됐다(H-1 과 같은 사고).
        limits.check("service-report:" + ClientAddress.of(request), 5);
        UUID id = UUID.randomUUID();
        // 비회원도 쓰는 경로다 — principal 은 로그인 상태에서만 채워진다(null 이면 익명 제보).
        Long userId = principal == null ? null : Long.valueOf(principal.getName());
        jdbc.update("""
                INSERT INTO service_report(id, category, description, page_url, source_url, user_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, body.category(), body.description().strip(), page, source, userId);
        return ApiResponse.ok(new Receipt(id, "PENDING", new Coupon(id, "UNUSED")));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
