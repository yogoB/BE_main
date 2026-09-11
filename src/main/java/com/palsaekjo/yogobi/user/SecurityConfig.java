package com.palsaekjo.yogobi.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    @ConditionalOnProperty(name = "yogobi.auth.google-enabled", havingValue = "true")
    ClientRegistrationRepository googleRegistration(@Value("${GOOGLE_CLIENT_ID}") String clientId,
            @Value("${GOOGLE_CLIENT_SECRET}") String secret, @Value("${GOOGLE_REDIRECT_URI}") String redirectUri) {
        if (clientId.isBlank() || secret.isBlank()) throw new IllegalStateException("Google credentials are required");
        var uri = java.net.URI.create(redirectUri);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                || !"/login/oauth2/code/google".equals(uri.getRawPath())
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost()))))
            throw new IllegalStateException("Google callback must be HTTPS (HTTP only for loopback development)");
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId).clientSecret(secret).redirectUri(redirectUri).scope("openid", "email").build());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain security(HttpSecurity http, AuthTokens tokens, AuthRateLimit limits, GoogleLogin google,
                                 ObjectMapper json, ObjectProvider<ClientRegistrationRepository> registrations) throws Exception {
        http.cors(Customizer.withDefaults())
                // OAuth/CSRF may use an HTTP session; that session must never authenticate a member API.
                .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(c -> c.disable()).formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable())
                .csrf(c -> c.ignoringRequestMatchers("/api/v1/recommendations", "/api/v1/calculator", "/api/v1/chat/messages"))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/account.html", "/account.js", "/favicon.ico", "/api/v1/catalog/**", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/recommendations", "/api/v1/calculator", "/api/v1/chat/messages",
                                "/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/email/verification",
                                "/api/v1/auth/password/reset-request", "/api/v1/auth/password/reset").permitAll()
                        .requestMatchers("/oauth2/authorization/google", "/login/oauth2/code/google").permitAll()
                        .requestMatchers("/api/v1/me", "/api/v1/me/**", "/api/v1/auth/logout", "/api/v1/auth/logout-all",
                                "/api/v1/auth/google/link", "/api/v1/auth/password").hasRole("MEMBER")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> error(json, res, AuthService.unauthorized()))
                        .accessDeniedHandler((req, res, ex) -> error(json, res,
                                new ApiException("YGB-AUTH-403", 403, "요청 권한과 보안 토큰을 확인해 주세요.", null))))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                            throws ServletException, IOException {
                        Long id = tokens.authenticate(req);
                        if (id != null) SecurityContextHolder.getContext().setAuthentication(
                                UsernamePasswordAuthenticationToken.authenticated(id.toString(), null,
                                        List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
                        try {
                            String path = req.getRequestURI();
                            if ((path.startsWith("/api/v1/auth/") && "POST".equals(req.getMethod()))
                                    || path.equals("/oauth2/authorization/google"))
                                limits.check("ip:" + req.getRemoteAddr(), 40); // Never trust caller-supplied X-Forwarded-For.
                            if (path.equals("/oauth2/authorization/google")) google.requireEnabled();
                            chain.doFilter(req, res);
                        } catch (ApiException ex) { error(json, res, ex); }
                    }
                }, CsrfFilter.class);
        if (registrations.getIfAvailable() != null) {
            var resolver = new org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver(
                    registrations.getObject(), "/oauth2/authorization");
            resolver.setAuthorizationRequestCustomizer(builder -> {
                org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
                builder.additionalParameters(parameters -> parameters.put("prompt", "select_account"));
            });
            var oidc = new OidcUserService();
            oidc.setRetrieveUserInfo(request -> false); // Validated ID token has all required claims; no Google API token storage.
            http.oauth2Login(o -> o.authorizationEndpoint(a -> a.authorizationRequestResolver(resolver))
                    .authorizedClientRepository(new org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository())
                    .userInfoEndpoint(u -> u.oidcUserService(oidc))
                    .successHandler(google::success).failureHandler((req, res, ex) -> google.failure(req, res)));
        }
        return http.build();
    }

    private static void error(ObjectMapper json, HttpServletResponse response, ApiException error) throws IOException {
        response.setStatus(error.status()); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getWriter(), new ErrorResponse(new ErrorResponse.Body(error.code(), error.getMessage(), error.field())));
    }
}
