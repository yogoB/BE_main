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

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService members;
    private final AuthTokens tokens;
    private final GoogleLogin google;
    private final AuthEmail emails;

    public AuthController(AuthService members, AuthTokens tokens, GoogleLogin google, AuthEmail emails) {
        this.members = members; this.tokens = tokens; this.google = google; this.emails = emails;
    }

    @GetMapping("/auth/csrf")
    public ApiResponse<Map<String, String>> csrf(CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }

    @PostMapping({"/auth/signup", "/auth/login"})
    public ApiResponse<AuthService.Member> local(@RequestBody JsonNode body, HttpServletRequest request,
                                               HttpServletResponse response) {
        boolean signup = request.getRequestURI().endsWith("/signup");
        fields(body, signup ? "token" : "email", "password");
        tokens.requireConfigured();
        String password = body.get("password").textValue();
        var member = signup ? members.signup(body.get("token").textValue(), password) : members.login(body.get("email").textValue(), password);
        tokens.issue(member.id(), member.credentialVersion(), request, response);
        GoogleLogin.invalidate(request);
        return ApiResponse.ok(member);
    }

    @PostMapping({"/auth/email/verification", "/auth/password/reset-request"})
    public ApiResponse<Map<String,String>> email(@RequestBody JsonNode body, HttpServletRequest request) {
        fields(body,"email"); tokens.requireConfigured();
        emails.request(body.get("email").textValue(), request.getRequestURI().endsWith("/verification")
                ? AuthEmail.Purpose.SIGNUP : AuthEmail.Purpose.RESET);
        return ApiResponse.ok(Map.of("message","입력한 이메일에서 본인 확인 안내를 확인해 주세요."));
    }

    @PostMapping("/auth/password/reset")
    public ApiResponse<Map<String,Boolean>> reset(@RequestBody JsonNode body, HttpServletRequest request, HttpServletResponse response) {
        fields(body,"token","password");
        members.resetPassword(body.get("token").textValue(),body.get("password").textValue());
        tokens.clear(response); GoogleLogin.invalidate(request);
        return ApiResponse.ok(Map.of("passwordReset",true));
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

    @PostMapping({"/auth/logout", "/auth/logout-all"})
    public ApiResponse<Map<String, Boolean>> logout(Principal principal, HttpServletRequest request,
                                                   HttpServletResponse response) {
        if (request.getRequestURI().endsWith("/logout-all")) tokens.revokeAll(Long.parseLong(principal.getName()));
        else tokens.revokeCurrent(request);
        tokens.clear(response);
        GoogleLogin.invalidate(request);
        return ApiResponse.ok(Map.of("loggedOut", true));
    }

    @PostMapping({"/auth/google/link", "/auth/password"})
    public ApiResponse<Map<String, String>> link(@RequestBody JsonNode body, Principal principal,
                                               HttpServletRequest request) {
        fields(body, "password");
        String url = google.begin(Long.parseLong(principal.getName()), body.get("password").textValue(),
                request.getRequestURI().endsWith("/password"), request);
        return ApiResponse.ok(Map.of("authorizationUrl", url));
    }

    private static void fields(JsonNode body, String... fields) {
        if (!body.isObject() || body.size() != fields.length)
            throw ApiException.requiredMissing(null, "요청 필드를 확인해 주세요.");
        for (String field : Set.of(fields)) if (!body.path(field).isTextual())
            throw ApiException.requiredMissing(field, "문자열로 입력해 주세요.");
    }
}
