package com.palsaekjo.yogobi.recommend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결과 화면의 "마이페이지에 저장"(D-51). 회원 전용, CSRF 필요(/api/v1/me/** 규칙 그대로).
 *
 * <p>저장하는 것은 <b>계산 요청</b>이고, 금액은 저장 시점에 BE 가 같은 계산기로 다시 만들어 스냅숏으로 둔다.
 * 화면이 보고 있던 숫자를 되돌려 받지 않는다 — 절대 원칙 2(금액은 BE 만 만든다)·4(출처)이고, 변조 방지다.
 * 스냅숏은 저장 당시 카탈로그 기준이라 나중에 요금이 바뀌어도 그때 본 값이 남는다. 그게 "저장"의 뜻이다.
 */
@RestController
@RequestMapping("/api/v1/me/saved-results")
public class SavedResultController {
    /** ponytail: 한 회원이 무한히 쌓지 못하게 하는 상한. 화면은 최신순 목록 하나라 이보다 많으면 어차피 안 본다. */
    static final int MAX_PER_MEMBER = 50;

    private final RecommendationService service;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final com.palsaekjo.yogobi.common.FunnelCounter funnel;

    public SavedResultController(RecommendationService service, JdbcTemplate jdbc, ObjectMapper json,
                                 com.palsaekjo.yogobi.common.FunnelCounter funnel) {
        this.service = service;
        this.jdbc = jdbc;
        this.json = json;
        this.funnel = funnel;
    }

    /** {@code monthlySavingsVsCurrent} 는 지금 쓰는 요금제 대비 절감액. 모르면 null — 0 으로 적지 않는다(D-53). */
    public record Saved(UUID id, Instant savedAt, CostResult cost, Long monthlySavingsVsCurrent) { }
    public record Deleted(boolean deleted) { }

    /** 본문은 계산기 요청과 같다({@code planId}·{@code tierIds}·{@code optional}). 없는 ID 는 계산기처럼 400·404 다. */
    @PostMapping
    public ApiResponse<Saved> save(@RequestBody CalculatorRequest request, Principal principal) throws Exception {
        long userId = Long.parseLong(principal.getName());
        CostResult cost = service.calculate(request).result();
        Long vsCurrent = savingsVsCurrent(request, cost);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM saved_result WHERE user_id = ?", Integer.class, userId);
        if (count != null && count >= MAX_PER_MEMBER)
            throw ApiException.conflict("저장한 결과는 " + MAX_PER_MEMBER + "개까지예요. 오래된 것을 지운 뒤 저장해 주세요.");
        funnel.record(com.palsaekjo.yogobi.common.FunnelCounter.RESULT_SAVED,
                com.palsaekjo.yogobi.common.FunnelCounter.actor(userId));   // 퍼널 5단계(D-52)
        return ApiResponse.ok(jdbc.queryForObject("""
                INSERT INTO saved_result(user_id, request, cost, monthly_savings_vs_current)
                VALUES (?, ?::jsonb, ?::jsonb, ?)
                RETURNING id, saved_at
                """, (rs, i) -> new Saved(rs.getObject("id", UUID.class), rs.getTimestamp("saved_at").toInstant(),
                        cost, vsCurrent),
                userId, json.writeValueAsString(request), json.writeValueAsString(cost), vsCurrent));
    }

    /**
     * 지금 쓰는 요금제 대비 절감액(D-53). 저장 요청에 {@code currentPlanId} 가 있을 때만 — 같은 계산기로
     * 그 요금제도 계산해 차액을 낸다. 없으면 null 이다. <b>0 으로 적지 않는다</b>: "절감이 없다"와
     * "모른다"는 다르고, 랜딩 표본은 후자를 빼야 한다.
     *
     * <p>정가 대비({@code cost.monthlySavings})를 쓰지 않는 이유: 알뜰폰은 정가 할인이 없어 대부분 0 이라
     * 사용자가 화면에서 본 숫자("지금보다 매달 N원")와 다르다.
     */
    private Long savingsVsCurrent(CalculatorRequest request, CostResult cost) {
        Long currentPlanId = request.optional() == null ? null : request.optional().currentPlanId();
        if (currentPlanId == null || currentPlanId.equals(request.planId())) {
            return null;
        }
        try {
            CostResult current = service.calculate(
                    new CalculatorRequest(currentPlanId, request.tierIds(), request.optional())).result();
            return current.monthlyTotal() - cost.monthlyTotal();
        } catch (ApiException e) {
            return null;   // 지금 요금제가 카탈로그에서 내려갔을 수 있다 — 저장 자체를 막지 않는다.
        }
    }

    /** 최신순. 스냅숏 그대로 — 다시 계산하지 않는다. */
    @GetMapping
    public ApiResponse<List<Saved>> list(Principal principal) {
        return ApiResponse.ok(jdbc.query("""
                SELECT id, saved_at, cost, monthly_savings_vs_current FROM saved_result
                WHERE user_id = ? ORDER BY saved_at DESC
                """, (rs, i) -> new Saved(rs.getObject("id", UUID.class), rs.getTimestamp("saved_at").toInstant(),
                        read(rs.getString("cost")), (Long) rs.getObject("monthly_savings_vs_current")),
                Long.parseLong(principal.getName())));
    }

    /** 남의 것은 없는 것과 같다 — 404 하나로 존재 여부를 알리지 않는다. */
    @DeleteMapping("/{id}")
    public ApiResponse<Deleted> delete(@PathVariable UUID id, Principal principal) {
        if (jdbc.update("DELETE FROM saved_result WHERE id = ? AND user_id = ?", id, Long.parseLong(principal.getName())) != 1)
            throw new ApiException("YGB-RES-404", 404, "저장한 결과를 찾을 수 없습니다.", null);
        return ApiResponse.ok(new Deleted(true));
    }

    private CostResult read(String snapshot) {
        try {
            return json.readValue(snapshot, CostResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("저장된 결과 스냅숏을 읽지 못했습니다", e);
        }
    }
}
