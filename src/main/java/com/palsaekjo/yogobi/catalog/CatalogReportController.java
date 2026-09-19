package com.palsaekjo.yogobi.catalog;

import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import com.palsaekjo.yogobi.common.ClientAddress;
import com.palsaekjo.yogobi.user.AuthRateLimit;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 공개 제보는 접수만 한다. 회원 정보·원문 IP를 저장하거나 제보 URL을 가져오지 않는다. */
@RestController
@RequestMapping("/api/v1/catalog/reports")
public class CatalogReportController {
    private static final Map<String, String> TARGETS = Map.of(
            "MOBILE_PLAN", "mobile_plan", "SUBSCRIPTION_SERVICE", "subscription_service",
            "SUBSCRIPTION_TIER", "subscription_tier", "BUNDLE_PRODUCT", "bundle_product");
    private static final Set<String> FIELDS = Set.of("PRICE", "DATA", "BENEFIT", "AVAILABILITY", "OTHER");
    private final JdbcTemplate jdbc;
    private final AuthRateLimit limits;

    public CatalogReportController(JdbcTemplate jdbc, AuthRateLimit limits) {
        this.jdbc = jdbc;
        this.limits = limits;
    }

    public record Request(String targetType, Long targetId, String field, String description, String sourceUrl) { }
    public record Receipt(UUID id, String status) { }

    @PostMapping
    public ApiResponse<Receipt> submit(@RequestBody Request body, HttpServletRequest request) {
        String table = body.targetType() == null ? null : TARGETS.get(body.targetType());
        if (table == null) throw ApiException.requiredMissing("targetType", "제보할 상품 종류를 선택하세요.");
        if (body.targetId() == null || body.targetId() <= 0) throw ApiException.requiredMissing("targetId", "제보할 상품이 필요합니다.");
        if (body.field() == null || !FIELDS.contains(body.field())) throw ApiException.requiredMissing("field", "잘못된 항목을 선택하세요.");
        if (body.description() == null || body.description().isBlank() || body.description().length() > 2000)
            throw ApiException.requiredMissing("description", "설명을 1~2000자로 입력하세요. 개인정보는 적지 마세요.");
        String source = body.sourceUrl() == null || body.sourceUrl().isBlank() ? null : body.sourceUrl().strip();
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
        limits.check("catalog-report:" + ClientAddress.of(request), 5);
        // table은 서버의 고정 allowlist 값이다. 사용자 문자열을 SQL 식별자로 사용하지 않는다.
        if (jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, body.targetId()) == 0)
            throw ApiException.planNotFound("제보할 상품을 찾을 수 없습니다.");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog_report(id,target_type,target_id,field,description,source_url)
                VALUES (?,?,?,?,?,?)
                """, id, body.targetType(), body.targetId(), body.field(), body.description().strip(), source);
        return ApiResponse.ok(new Receipt(id, "PENDING"));
    }
}
