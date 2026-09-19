package com.palsaekjo.yogobi.admin;

import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import com.palsaekjo.yogobi.user.AuthTokens;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 운영(D-57 ⑥). CS 가 들어왔을 때 DB 를 직접 여는 대신 여기서 본다 — 누가 언제 가입했고,
 * 세션이 몇 개 살아 있고, 저장·구독이 몇 건인지.
 *
 * <p><b>운영자가 회원을 지우지 않는다.</b> 탈퇴는 본인만 한다(`DELETE /api/v1/me`, 처리방침).
 * 여기서 할 수 있는 것은 <b>세션 회수</b>뿐이다 — 기기를 잃었다는 문의에 답하는 수단이고,
 * 그 행위는 {@code admin_action} 에 남는다.
 *
 * <p>비밀번호·토큰은 애초에 없다(D-34 Google 전용, 세션은 SHA-256 지문만 저장).
 */
@RestController
@RequestMapping("/api/v1/admin/members")
public class MemberBoardController {
    private static final int MAX_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final AuthTokens tokens;
    private final AdminActions actions;

    public MemberBoardController(JdbcTemplate jdbc, AuthTokens tokens, AdminActions actions) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.actions = actions;
    }

    public record Member(long id, String email, String nickname, Instant createdAt,
                         int activeSessions, int savedResults, int subscriptions, String currentPlan) { }

    /** {@code q} 는 이메일·닉네임 부분 일치. 비우면 최근 가입순이다. */
    @GetMapping
    public ApiResponse<List<Member>> list(@RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "50") int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        String like = q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase() + "%";
        return ApiResponse.ok(jdbc.query("""
                SELECT u.id, u.email, u.nickname, u.created_at,
                       (SELECT count(*) FROM auth_session s WHERE s.user_id = u.id AND s.expires_at > now()) AS sessions,
                       (SELECT count(*) FROM saved_result r WHERE r.user_id = u.id) AS saved,
                       (SELECT count(*) FROM user_subscription x WHERE x.user_id = u.id AND x.ended_at IS NULL) AS subs,
                       p.name AS current_plan
                  FROM app_user u LEFT JOIN mobile_plan p ON p.id = u.current_plan_id
                 WHERE CAST(? AS text) IS NULL
                    OR lower(u.email) LIKE CAST(? AS text) OR lower(coalesce(u.nickname, '')) LIKE CAST(? AS text)
                 ORDER BY u.created_at DESC LIMIT ?
                """, (rs, i) -> new Member(rs.getLong("id"), rs.getString("email"), rs.getString("nickname"),
                        rs.getTimestamp("created_at").toInstant(), rs.getInt("sessions"), rs.getInt("saved"),
                        rs.getInt("subs"), rs.getString("current_plan")),
                like, like, like, capped));
    }

    /** 그 회원의 로그인 세션을 전부 끊는다. 기기 분실 문의에 답하는 수단이다. */
    @DeleteMapping("/{id}/sessions")
    public ApiResponse<Map<String, Object>> revokeSessions(@PathVariable long id, Principal principal) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0) throw new ApiException("YGB-REQ-404", 404, "회원을 찾을 수 없어요.", "id");
        tokens.revokeAll(id);
        actions.record(Long.parseLong(principal.getName()), "MEMBER_SESSIONS_REVOKED", "member:" + id, null);
        return ApiResponse.ok(Map.of("id", id, "revoked", true));
    }
}
