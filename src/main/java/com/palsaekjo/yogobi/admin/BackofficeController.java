package com.palsaekjo.yogobi.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import com.palsaekjo.yogobi.catalog.ExchangeRates;
import com.palsaekjo.yogobi.privacy.RetentionService;
import com.palsaekjo.yogobi.recommend.SmartChoiceSweepService;
import com.palsaekjo.yogobi.user.AdminAccount;
import com.palsaekjo.yogobi.user.AuthService;
import com.palsaekjo.yogobi.user.AuthTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 백오피스(D-32). **별도 서버를 두지 않는다** — 관리 화면은 프론트 앱의 `/admin` 라우트(D-39)고,
 * 기능은 전부 여기 API 다. 두 축뿐이다: ① 사용 지표 대시보드 ② 매일 09시 수집분 검수.
 *
 * <p>검수 기능은 새로 만들지 않고 기존 카탈로그 변경 제안·승인(D-28)·자동검토(D-29)를 그대로 쓴다
 * (`/api/v1/admin/catalog/requests`). 여기서는 로그인·지표·수집 실행만 담당한다.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class BackofficeController {
    private final AdminAccount admin;
    private final AuthTokens tokens;
    private final BackofficeMetrics metrics;
    private final CatalogDailyHarvest harvest;
    private final SmartChoiceSweepService sweep;
    private final ExchangeRates rates;
    private final RetentionService retention;
    private final AdminActions actions;
    private final JdbcTemplate jdbc;

    public BackofficeController(AdminAccount admin, AuthTokens tokens, BackofficeMetrics metrics,
                                CatalogDailyHarvest harvest, SmartChoiceSweepService sweep,
                                ExchangeRates rates, RetentionService retention,
                                AdminActions actions, JdbcTemplate jdbc) {
        this.actions = actions;
        this.jdbc = jdbc;
        this.admin = admin;
        this.tokens = tokens;
        this.metrics = metrics;
        this.harvest = harvest;
        this.sweep = sweep;
        this.rates = rates;
        this.retention = retention;
    }

    /**
     * 관리자 로그인. 회원 로그인과 **같은 쿠키·JWT**를 발급하되 아이디로 인증한다.
     * 실패는 401 하나로 통일한다 — 아이디가 있는지 없는지 알려주지 않는다.
     */
    @PostMapping("/login")
    public ApiResponse<AuthService.Member> login(@RequestBody JsonNode body,
                                                 HttpServletRequest request, HttpServletResponse response) {
        if (!admin.configured()) throw AdminAccount.notConfigured();
        if (!body.path("id").isTextual() || !body.path("password").isTextual())
            throw ApiException.requiredMissing("id", "아이디와 비밀번호를 입력해 주세요.");
        var member = admin.login(body.get("id").textValue(), body.get("password").textValue());
        tokens.issue(member.id(), member.credentialVersion(), request, response);
        return ApiResponse.ok(member);
    }

    /** 로그인한 계정이 관리자인지. 화면이 진입 시 확인한다(회원 쿠키로는 false). */
    @GetMapping("/session")
    public ApiResponse<Map<String, Object>> session(Principal principal) {
        long id = Long.parseLong(principal.getName());
        return ApiResponse.ok(Map.of("userId", id, "admin", true, "loginId", metrics.loginId(id)));
    }

    /** ① 사용 지표 대시보드. */
    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        return ApiResponse.ok(metrics.dashboard());
    }

    /** ② 수집을 지금 한 번 돌린다(정기 실행은 매일 09:00 KST). 데모·긴급 보정용. */
    @PostMapping("/harvest/run")
    public ApiResponse<Map<String, Object>> runHarvest(Principal principal) {
        var out = harvest.harvest();
        actions.record(Long.parseLong(principal.getName()), "JOB_HARVEST", null, String.valueOf(out));
        return ApiResponse.ok(out);
    }

    /**
     * 시세 스냅샷을 지금 한 번 모은다(정기 실행은 03:40·12:40·20:40 KST).
     *
     * <p>수집(②)보다 **먼저** 돌려야 하는 작업이다 — 수집은 이 스냅샷과 카탈로그를 비교하므로
     * 스냅샷이 비어 있으면 아무 제안도 만들지 못한다. 키를 새로 넣었을 때도 여기서 바로 확인한다.
     *
     * <p>배포 머신이 유휴 시 정지(`auto_stop_machines`)라 예약 시각에 잠들어 있으면 스케줄이
     * 발화하지 않는다. 그래서 이 수동 경로가 실질적인 실행 수단이다.
     */
    @PostMapping("/smartchoice/sweep")
    public ApiResponse<Map<String, Object>> runSweep(Principal principal) {
        var out = sweep.sweep();
        actions.record(Long.parseLong(principal.getName()), "JOB_SMARTCHOICE", null, String.valueOf(out));
        return ApiResponse.ok(out);
    }

    /** 환율을 지금 갱신한다(정기 실행은 09:15 KST). 실패해도 이전 값이 남는다 — 응답의 updated 로 구분한다. */
    @PostMapping("/fx/refresh")
    public ApiResponse<Map<String, Object>> refreshFx(Principal principal) {
        var out = rates.refreshNow();
        actions.record(Long.parseLong(principal.getName()), "JOB_FX", null, String.valueOf(out));
        return ApiResponse.ok(out);
    }

    /**
     * 파기 대상이 지금 몇 건인지만 센다. 지우지 않는다.
     * 파기는 되돌릴 수 없으므로 화면은 항상 이걸 먼저 보여준다.
     */
    @GetMapping("/retention/pending")
    public ApiResponse<Map<String, Integer>> retentionPending() {
        return ApiResponse.ok(retention.pending());
    }

    /**
     * 보유기간이 지난 개인정보를 지금 파기한다(정기 실행은 04:00 KST).
     *
     * <p><b>되돌릴 수 없다.</b> 그래서 본문에 {@code {"confirm":"파기"}} 를 요구한다 —
     * 실수로 누른 버튼 하나로 개인 데이터가 사라지지 않게 하는 마지막 관문이다.
     * 무엇이 지워졌는지 유형별 건수로 돌려주므로 운영자가 기록으로 남길 수 있다.
     */
    @PostMapping("/retention/purge")
    public ApiResponse<Map<String, Integer>> runRetention(@RequestBody JsonNode body, Principal principal) {
        if (!"파기".equals(body.path("confirm").asText(null))) {
            throw ApiException.requiredMissing("confirm", "되돌릴 수 없는 작업이에요. 확인 문구를 입력해 주세요.");
        }
        var out = retention.purge();
        actions.record(Long.parseLong(principal.getName()), "JOB_PURGE", null, String.valueOf(out));
        return ApiResponse.ok(out);
    }

    /**
     * 감사 타임라인(D-52 ⑧). 카탈로그 변경(`catalog_audit`)과 그 밖의 운영 행위(`admin_action`)를 한 줄로 본다.
     * 운영자는 로그인 아이디(이메일)로 보인다 — 운영자끼리의 기록이라 신원 은닉 대상이 아니다.
     */
    @GetMapping("/audit")
    public ApiResponse<java.util.List<Map<String, Object>>> audit(@RequestParam(defaultValue = "100") int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        return ApiResponse.ok(jdbc.queryForList("""
                SELECT * FROM (
                    SELECT a.created_at AS at, u.email AS actor, 'CATALOG_' || a.action AS action,
                           a.dataset || ':' || a.row_key AS target,
                           CASE WHEN a.outcome = 'APPLIED' THEN NULL ELSE a.outcome || ' ' || coalesce(a.detail, '') END AS detail
                    FROM catalog_audit a LEFT JOIN app_user u ON u.id = a.actor_id
                    UNION ALL
                    SELECT b.created_at, u.email, b.action, b.target, b.detail
                    FROM admin_action b LEFT JOIN app_user u ON u.id = b.actor_id
                ) t ORDER BY at DESC LIMIT ?
                """, capped));
    }
}
