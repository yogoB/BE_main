package com.palsaekjo.yogobi.user;

import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.FunnelCounter;
import com.palsaekjo.yogobi.privacy.ConsentService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/** 가입·로그인의 유일한 경로다(D-34). 자체 계정이 없으므로 연결(link)할 것도 없다. */
@Component
public class GoogleLogin {
    private static final String CONSENT = GoogleLogin.class.getName() + ".consent";
    private final AuthService members;
    private final AuthTokens tokens;
    private final FunnelCounter funnel;
    private final boolean enabled;
    private final String returnUrl;

    public GoogleLogin(AuthService members, AuthTokens tokens, FunnelCounter funnel,
                       @Value("${yogobi.auth.google-enabled:false}") boolean enabled,
                       @Value("${yogobi.auth.return-url:http://localhost:5173/}") String returnUrl) {
        this.members = members; this.tokens = tokens; this.funnel = funnel; this.enabled = enabled;
        var uri = java.net.URI.create(returnUrl);
        if (uri.getHost() == null || uri.getRawFragment() != null || uri.getUserInfo() != null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost()))))
            throw new IllegalStateException("Auth return URL must be HTTPS (HTTP only for loopback development)");
        this.returnUrl = returnUrl;
    }

    public void requireEnabled() {
        tokens.requireConfigured();
        if (!enabled) throw new ApiException("YGB-AUTH-503", 503, "Google 로그인 설정을 확인해 주세요.", null);
    }

    public void rememberConsent(HttpServletRequest request, JsonNode body) {
        var fields = java.util.Set.of("age14", "terms", "privacy", "savingsAlerts", "marketing");
        if (!body.isObject() || body.size() != fields.size()
                || fields.stream().anyMatch(field -> !body.path(field).isBoolean()))
            throw ApiException.requiredMissing(null, "동의 항목을 확인해 주세요.");
        for (String required : java.util.List.of("age14", "terms", "privacy"))
            if (!body.path(required).booleanValue())
                throw ApiException.requiredMissing(required, "필수 동의 항목을 확인해 주세요.");
        request.getSession(true).setAttribute(CONSENT,
                new ConsentService.LoginConsent(body.path("savingsAlerts").booleanValue(), body.path("marketing").booleanValue()));
    }

    public void requireConsent(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(CONSENT) instanceof ConsentService.LoginConsent))
            throw ApiException.requiredMissing("consent", "Google 로그인 전에 필수 동의 항목을 확인해 주세요.");
    }

    public void success(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
        try {
            OidcUser google = (OidcUser) authentication.getPrincipal();
            // Google sub 가 있으면 로그인, 없으면 가입이다 — 사용자에게는 같은 버튼 하나다(D-34).
            requireConsent(request);
            var choices = (ConsentService.LoginConsent) request.getSession(false).getAttribute(CONSENT);
            var member = members.googleLogin(google, choices);
            tokens.issue(member.id(), member.credentialVersion(), request, response);
            funnel.record(FunnelCounter.MEMBER_LOGIN, FunnelCounter.actor(member.id()));   // 가입·로그인이 같은 경로다(D-34)
            invalidate(request);
            response.sendRedirect(returnUrl + "#auth=success");
        } catch (ApiException e) {
            invalidate(request);
            response.sendRedirect(returnUrl + "#auth=" + (e.status() == 409 ? "account-conflict" : "failed"));
        } finally { SecurityContextHolder.clearContext(); }
    }

    public void failure(HttpServletRequest request, HttpServletResponse response) throws IOException {
        invalidate(request);
        SecurityContextHolder.clearContext();
        response.sendRedirect(returnUrl + "#auth=failed");
    }

    public static void invalidate(HttpServletRequest request) {
        if (request.getSession(false) != null) request.getSession(false).invalidate();
    }
}
