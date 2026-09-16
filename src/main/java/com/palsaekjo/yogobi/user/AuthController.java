package com.palsaekjo.yogobi.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/**
 * 가입·로그인은 Google OAuth 하나뿐이다(D-34) — 그 경로는 Spring Security 가 처리하고
 * ({@code /oauth2/authorization/google} → {@link GoogleLogin#success}) 여기에는 남지 않는다.
 * 이 컨트롤러가 가진 것은 로그인 이후의 회원 조회·세션 관리·탈퇴다.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService members;
    private final AuthTokens tokens;

    public AuthController(AuthService members, AuthTokens tokens) {
        this.members = members; this.tokens = tokens;
    }

    @GetMapping("/auth/csrf")
    public ApiResponse<Map<String, String>> csrf(CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }

    @GetMapping("/me/sessions")
    public ApiResponse<java.util.List<AuthTokens.Session>> sessions(Principal principal,HttpServletRequest request) {
        return ApiResponse.ok(tokens.sessions(Long.parseLong(principal.getName()),request));
    }

    @DeleteMapping("/me/sessions/{sessionId}")
    public ApiResponse<Map<String,Boolean>> revokeSession(@PathVariable java.util.UUID sessionId, Principal principal,
                                                        HttpServletRequest request,HttpServletResponse response) {
        tokens.revokeSession(Long.parseLong(principal.getName()),sessionId);
        if (tokens.authenticate(request) == null) { tokens.clear(response); GoogleLogin.invalidate(request); }
        return ApiResponse.ok(Map.of("revoked",true));
    }

    @GetMapping("/me")
    public ApiResponse<AuthService.Member> me(Principal principal) {
        return ApiResponse.ok(members.member(Long.parseLong(principal.getName())));
    }

    @DeleteMapping("/me")
    public ApiResponse<Map<String, Boolean>> deleteAccount(Principal principal, HttpServletRequest request,
                                                           HttpServletResponse response) {
        members.deleteAccount(Long.parseLong(principal.getName()));
        tokens.clear(response);
        GoogleLogin.invalidate(request);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @PostMapping({"/auth/logout", "/auth/logout-all"})
    public ApiResponse<Map<String, Boolean>> logout(Principal principal, HttpServletRequest request,
                                                   HttpServletResponse response) {
        if (request.getRequestURI().endsWith("/logout-all")) tokens.revokeAll(Long.parseLong(principal.getName()));
        else tokens.revokeCurrent(request);
        tokens.clear(response);
        GoogleLogin.invalidate(request);
        return ApiResponse.ok(Map.of("loggedOut", true));
    }

    /** 닉네임 변경(D-22). 회원 본인만. */
    @PostMapping("/me/nickname")
    public ApiResponse<AuthService.Member> nickname(@RequestBody JsonNode body, java.security.Principal principal) {
        fields(body, "nickname");
        return ApiResponse.ok(members.changeNickname(Long.parseLong(principal.getName()),
                body.get("nickname").textValue()));
    }

    private static void fields(JsonNode body, String... fields) {
        if (!body.isObject() || body.size() != fields.length)
            throw ApiException.requiredMissing(null, "요청 필드를 확인해 주세요.");
        for (String field : Set.of(fields)) if (!body.path(field).isTextual())
            throw ApiException.requiredMissing(field, "문자열로 입력해 주세요.");
    }
}
