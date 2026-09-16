package com.palsaekjo.yogobi.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import com.palsaekjo.yogobi.recommend.SmartChoiceSweepService;
import com.palsaekjo.yogobi.user.AdminAccount;
import com.palsaekjo.yogobi.user.AuthService;
import com.palsaekjo.yogobi.user.AuthTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 백오피스(D-32). **별도 서버를 두지 않는다** — 관리 화면은 이 서버가 내려주는 정적 페이지(`/admin.html`)고,
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

    public BackofficeController(AdminAccount admin, AuthTokens tokens, BackofficeMetrics metrics,
                                CatalogDailyHarvest harvest, SmartChoiceSweepService sweep) {
        this.admin = admin;
        this.tokens = tokens;
        this.metrics = metrics;
        this.harvest = harvest;
        this.sweep = sweep;
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
    public ApiResponse<Map<String, Object>> runHarvest() {
        return ApiResponse.ok(harvest.harvest());
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
    public ApiResponse<Map<String, Object>> runSweep() {
        return ApiResponse.ok(sweep.sweep());
    }
}
