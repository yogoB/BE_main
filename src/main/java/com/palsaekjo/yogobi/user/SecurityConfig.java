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
import org.springframework.core.annotation.Order;
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

    /**
     * 액추에이터(D-25)는 아래 회원 체인의 denyAll 에 걸리므로 별도 체인으로 연다.
     * 열리는 곳은 관리 포트(`management.server.port`)뿐이다 — 공개 포트에는 이 경로가 매핑되지 않으므로
     * permitAll 이어도 404 가 나고, 그 404 의 /error 디스패치를 아래 체인이 다시 막아 **401** 로 끝난다
     * (배포본에서 확인). 그 경계를 MetricsEndpointTest 가 지킨다.
     */
    @Bean
    @Order(0)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain actuator(HttpSecurity http) throws Exception {
        return http.securityMatcher("/actuator", "/actuator/**")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(c -> c.disable()).cors(c -> c.disable()).build();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain security(HttpSecurity http, AuthTokens tokens, AuthRateLimit limits, GoogleLogin google,
                                 ObjectMapper json, ObjectProvider<ClientRegistrationRepository> registrations,
                                 CatalogOperators operators) throws Exception {
        http.cors(Customizer.withDefaults())
                // OAuth/CSRF may use an HTTP session; that session must never authenticate a member API.
                .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(c -> c.disable()).formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable())
                .csrf(c -> c.ignoringRequestMatchers("/api/v1/recommendations", "/api/v1/calculator"))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/account.html", "/account.js", "/catalog-report.js", "/favicon.ico", "/api/v1/catalog/**", "/api/v1/auth/csrf", "/api/v1/privacy-policy").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/recommendations", "/api/v1/calculator",
                                "/api/v1/catalog/reports", "/api/v1/reports",
                                // 백오피스 로그인만 공개다(D-32). 나머지 /admin/** 은 아래에서 ADMIN 전용.
                                // 가입·로그인은 Google 하나뿐이다(D-34) — 아래 /oauth2 경로가 그 입구다.
                                "/api/v1/admin/login").permitAll()
                        .requestMatchers("/oauth2/authorization/google", "/login/oauth2/code/google").permitAll()
                        .requestMatchers("/api/v1/me", "/api/v1/me/**",
                                "/api/v1/auth/logout", "/api/v1/auth/logout-all").hasRole("MEMBER")
                        // 카탈로그 원본(합본 CSV) CRUD — 공개 읽기 경로와 분리하고 **운영자만** 허용한다(D-24).
                        // CSRF 보호는 기본값 그대로 적용된다. 운영자 지정은 CATALOG_ADMIN_USER_IDS(비면 아무도 못 쓴다).
                        .requestMatchers("/api/v1/admin", "/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> error(json, res, AuthService.unauthorized()))
                        .accessDeniedHandler((req, res, ex) -> error(json, res,
                                new ApiException("YGB-AUTH-403", 403, "요청 권한과 보안 토큰을 확인해 주세요.", null))))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                            throws ServletException, IOException {
                        Long id = tokens.authenticate(req);
                        // 운영자로 지정된 회원만 ROLE_ADMIN을 더 받는다(D-24). 그 외에는 기존과 동일하게 MEMBER뿐이다.
                        if (id != null) SecurityContextHolder.getContext().setAuthentication(
                                UsernamePasswordAuthenticationToken.authenticated(id.toString(), null,
                                        operators.contains(id)
                                                ? List.of(new SimpleGrantedAuthority("ROLE_MEMBER"),
                                                        new SimpleGrantedAuthority("ROLE_ADMIN"))
                                                : List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
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
