package com.palsaekjo.yogobi.user;

import com.palsaekjo.yogobi.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

@Component
public class GoogleLogin {
    private static final String INTENT = GoogleLogin.class.getName();
    private final AuthService members;
    private final AuthTokens tokens;
    private final boolean enabled;
    private final String returnUrl;
    private record Intent(long userId, String sessionHash, Instant expires, String passwordHash, long version) { }

    public GoogleLogin(AuthService members, AuthTokens tokens,
                       @Value("${yogobi.auth.google-enabled:false}") boolean enabled,
                       @Value("${yogobi.auth.return-url:http://localhost:5173/}") String returnUrl) {
        this.members = members; this.tokens = tokens; this.enabled = enabled;
        var uri = java.net.URI.create(returnUrl);
        if (uri.getHost() == null || uri.getRawFragment() != null || uri.getUserInfo() != null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost()))))
            throw new IllegalStateException("Auth return URL must be HTTPS (HTTP only for loopback development)");
        this.returnUrl = returnUrl;
    }

    public String begin(long id, String password, boolean addPassword, HttpServletRequest request) {
        requireEnabled();
        String hash = null;
        long version;
        if (addPassword) {
            var member = members.member(id);
            if (member.localLogin() || !member.googleLogin()) throw AuthService.conflict();
            hash = members.encodePassword(password);
            version = member.credentialVersion();
        } else version = members.verifyPassword(id, password);
        request.getSession().setAttribute(INTENT, new Intent(id, tokens.sessionHash(request), Instant.now().plusSeconds(300), hash, version));
        request.changeSessionId();
        return "/oauth2/authorization/google";
    }

    public void requireEnabled() {
        tokens.requireConfigured();
        if (!enabled) throw new ApiException("YGB-AUTH-503", 503, "Google 로그인 설정을 확인해 주세요.", null);
    }

    public void success(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
        try {
            OidcUser google = (OidcUser) authentication.getPrincipal();
            var session = request.getSession(false);
            Intent intent = session == null ? null : (Intent) session.getAttribute(INTENT);
            if (session != null) session.removeAttribute(INTENT);
            AuthService.Member member;
            if (intent == null) member = members.googleLogin(google);
            else {
                Long id = tokens.authenticate(request);
                if (id == null || id != intent.userId() || !Instant.now().isBefore(intent.expires())
                        || !tokens.sessionHash(request).equals(intent.sessionHash())) throw AuthService.unauthorized();
                member = intent.passwordHash() == null ? members.linkGoogle(id, google, intent.version())
                        : members.addPassword(id, intent.passwordHash(), google, intent.version());
                tokens.revokeAll(id); // Credential linking retires previously issued sessions.
            }
            tokens.issue(member.id(), member.credentialVersion(), request, response);
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
