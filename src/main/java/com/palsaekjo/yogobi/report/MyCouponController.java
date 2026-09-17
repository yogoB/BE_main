package com.palsaekjo.yogobi.report;

import com.palsaekjo.yogobi.common.ApiResponse;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 제보 리워드 쿠폰함. <b>로그인 상태로 낸 제보만</b> 여기 보인다 — 비로그인 제보는 회원 id 가 없어
 * 누구 것인지 알 수 없고, 그쪽은 접수 때 받은 코드를 직접 보관하는 방식이다.
 *
 * <p><b>조회만 있다.</b> 이 쿠폰이 오늘 해제하는 것은 없다 — 요금 분석은 무료이고 수익 모델은
 * 범위 밖이다(D-01). 사용 API 는 쓸 곳이 생기는 날 만든다. 지금 만들면 쓰지 않을 코드다.
 *
 * <p>보유기간은 제보와 같다 — 90일 뒤 본문과 함께 파기되고 쿠폰도 같이 사라진다(RetentionService).
 * 탈퇴하면 귀속만 끊겨(V24 의 ON DELETE SET NULL) 이 목록에서 빠진다.
 *
 * <p>인증은 `/api/v1/me/**` 규칙이 이미 ROLE_MEMBER 로 막고 있어 여기서 따로 확인하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/me/coupons")
public class MyCouponController {
    private final JdbcTemplate jdbc;

    public MyCouponController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** code 는 제보 id 와 같은 값이다(V24) — 제보 1건 = 쿠폰 1장이라 코드를 따로 두지 않았다. */
    public record Coupon(UUID code, String status, Instant issuedAt, Instant usedAt) { }

    @GetMapping
    public ApiResponse<List<Coupon>> myCoupons(Principal principal) {
        return ApiResponse.ok(jdbc.query("""
                SELECT id, created_at, coupon_used_at FROM service_report
                WHERE user_id = ?
                ORDER BY created_at DESC
                """,
                (rs, row) -> {
                    var used = rs.getTimestamp("coupon_used_at");
                    return new Coupon(
                            rs.getObject("id", UUID.class),
                            used == null ? "UNUSED" : "USED",
                            rs.getTimestamp("created_at").toInstant(),
                            used == null ? null : used.toInstant());
                },
                Long.parseLong(principal.getName())));
    }
}
