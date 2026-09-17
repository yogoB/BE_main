package com.palsaekjo.yogobi.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real security filters, PostgreSQL, JWT signatures and an HTTP OIDC provider; no mocked authentication. */
@SpringBootTest(properties = {"JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh",
        "yogobi.auth.ip-limit=40",   // 운영 기본은 2000(H-1). 41번째를 보려고 내린다
        "yogobi.auth.google-enabled=true", "GOOGLE_CLIENT_ID=test-client", "GOOGLE_CLIENT_SECRET=test-secret",
        "GOOGLE_REDIRECT_URI=https://localhost/login/oauth2/code/google", "yogobi.auth.return-url=https://frontend.example/",
        // 로컬 .env(spring.config.import)가 무엇이든 보안 쿠키 단언은 여기서 고정한다.
        "AUTH_SECURE_COOKIES=true", "AUTH_SESSION_COOKIE_NAME=__Host-YGB_SESSION"})
@AutoConfigureMockMvc
@Testcontainers
@Import(AuthSecurityTest.ProviderConfig.class)
class AuthSecurityTest {
    static final String SECRET = "VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    static final ObjectMapper JSON = new ObjectMapper();
    static final RSAKey RSA;
    static final HttpServer PROVIDER;
    static final Map<String, Grant> GRANTS = new ConcurrentHashMap<>();
    record Grant(String jwt, String challenge) { }
    static {
        try {
            RSA = new RSAKeyGenerator(2048).keyID("test-key").generate();
            PROVIDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PROVIDER.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(RSA.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            PROVIDER.createContext("/token", exchange -> {
                var form = params(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Grant grant = GRANTS.remove(form.get("code"));
                String actual = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        HexFormat.of().parseHex(AuthTokens.hash(form.getOrDefault("code_verifier", ""))));
                boolean valid = grant != null && actual.equals(grant.challenge());
                byte[] body = JSON.writeValueAsBytes(valid ? Map.of("access_token", "provider-access-token", "token_type", "Bearer",
                        "expires_in", 300, "id_token", grant.jwt()) : Map.of("error", "invalid_grant"));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(valid ? 200 : 400, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            PROVIDER.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    static String issuer() { return "http://127.0.0.1:" + PROVIDER.getAddress().getPort(); }
    @TestConfiguration static class ProviderConfig {
        @Bean @Primary ClientRegistrationRepository testProvider() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google")
                    .clientId("test-client").clientSecret("test-secret").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("https://localhost/login/oauth2/code/google").scope("openid", "email")
                    .authorizationUri(issuer() + "/authorize").tokenUri(issuer() + "/token").jwkSetUri(issuer() + "/jwks")
                    .issuerUri(issuer()).userNameAttributeName("sub").clientName("Test Google").build());
        }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthTokens tokens;
    @Autowired AuthService members;

    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE app_user, auth_rate_limit CASCADE"); GRANTS.clear();
        jdbc.update("DELETE FROM funnel_daily");   // D-36 퍼널은 테스트마다 0 에서 센다
    }
    @AfterAll static void stop() { PROVIDER.stop(0); }

    class Browser {
        Cookie[] cookies = {};
        MockHttpSession session;
        String csrf;
        void csrf() throws Exception {
            var req = withCookies(get("/api/v1/auth/csrf"), cookies);
            if (session != null && !session.isInvalid()) req.session(session);
            var result = mvc.perform(req).andExpect(status().isOk()).andReturn();
            session = (MockHttpSession) result.getRequest().getSession(false);
            csrf = JSON.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
        }
        ResultActions post(String path, Object body) throws Exception {
            csrf();
            return mvc.perform(withCookies(postJson(path, body).session(session), cookies).header("X-CSRF-TOKEN", csrf));
        }
        void accept(MvcResult result) {
            Cookie[] issued = result.getResponse().getCookies();
            if (issued.length > 0) cookies = issued;
            if (session != null && session.isInvalid()) session = null;
        }
        ResultActions me() throws Exception { return mvc.perform(get("/api/v1/me").cookie(cookies)); }
    }
    static MockHttpServletRequestBuilder postJson(String path, Object body) throws Exception {
        return post(path).contentType("application/json").content(JSON.writeValueAsBytes(body));
    }
    static MockHttpServletRequestBuilder withCookies(MockHttpServletRequestBuilder request, Cookie[] cookies) {
        return cookies.length == 0 ? request : request.cookie(cookies);
    }
    /**
     * D-34: 가입·로그인은 Google 하나뿐이다. 이메일당 고정 sub 를 써서 같은 사람을 반복 로그인시킨다
     * — 두 번 부르면 같은 회원의 세션이 하나 더 생긴다.
     */
    Browser signup(String email) throws Exception {
        return google("google-" + AuthService.email(email), email);
    }
    static Map<String,String> params(String query) {
        Map<String,String> result = new HashMap<>();
        for (String pair : query.split("&")) { String[] kv = pair.split("=", 2);
            result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length == 1 ? "" : URLDecoder.decode(kv[1], StandardCharsets.UTF_8)); }
        return result;
    }
    record Flow(Browser browser, Map<String,String> query, MockHttpSession session) { }
    Flow start(Browser browser) throws Exception {
        var req = withCookies(get("/oauth2/authorization/google"), browser.cookies);
        if (browser.session != null && !browser.session.isInvalid()) req.session(browser.session);
        var r = mvc.perform(req).andExpect(status().is3xxRedirection()).andReturn();
        var query = params(URI.create(r.getResponse().getRedirectedUrl()).getRawQuery());
        assertEquals("S256", query.get("code_challenge_method"));
        assertNotNull(query.get("nonce")); assertNotNull(query.get("state"));
        return new Flow(browser, query, (MockHttpSession) r.getRequest().getSession(false));
    }
    String grant(Flow flow, String subject, String email, Consumer<JWTClaimsSet.Builder> modify, RSAKey key) throws Exception {
        var builder = new JWTClaimsSet.Builder().issuer(issuer()).subject(subject).audience("test-client")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("nonce", flow.query().get("nonce")).claim("email", email).claim("email_verified", true);
        modify.accept(builder);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), builder.build());
        jwt.sign(new RSASSASigner(key));
        String code = UUID.randomUUID().toString(); GRANTS.put(code, new Grant(jwt.serialize(), flow.query().get("code_challenge")));
        return code;
    }
    ResultActions callback(Flow flow, String code) throws Exception {
        return mvc.perform(withCookies(get("/login/oauth2/code/google").param("state", flow.query().get("state")).param("code", code)
                .session(flow.session()), flow.browser().cookies));
    }
    Browser google(String sub, String email) throws Exception {
        Browser b = new Browser(); Flow flow = start(b);
        var r = callback(flow, grant(flow, sub, email, claims -> {}, RSA))
                .andExpect(redirectedUrl("https://frontend.example/#auth=success")).andReturn();
        b.accept(r); return b;
    }

    @Test void membersAreIsolatedAndRequestParametersCannotImpersonate() throws Exception {
        Browser a = signup("Alice@Example.com"), b = signup("bob@example.com");
        a.me().andExpect(jsonPath("$.data.email").value("alice@example.com"));
        b.me().andExpect(jsonPath("$.data.email").value("bob@example.com"));
        mvc.perform(get("/api/v1/me").cookie(a.cookies).param("userId", "999"))
                .andExpect(jsonPath("$.data.email").value("alice@example.com"));
        mvc.perform(get("/api/v1/me/999").cookie(a.cookies)).andExpect(status().isNotFound());
        // D-34: 회원 비밀번호는 아예 보관하지 않는다.
        assertNull(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email='alice@example.com'", String.class));
        a.me().andExpect(jsonPath("$.data.localLogin").value(false))
                .andExpect(jsonPath("$.data.googleLogin").value(true));
    }

    @Test void guestEndpointsRemainPublicAndMemberEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/catalog/services")).andExpect(status().isOk());
        for (String path : List.of("/api/v1/recommendations", "/api/v1/calculator"))
            mvc.perform(post(path).contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/subscriptions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/detections")).andExpect(status().isUnauthorized());
    }

    @Test void cookiesAreHttpOnlyHostOnlySecureAndTokensNeverAppearInJson() throws Exception {
        Flow flow = start(new Browser());
        var r = callback(flow, grant(flow, "google-1", "alice@example.com", claims -> {}, RSA))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        for (Cookie c : r.getResponse().getCookies()) {
            assertTrue(c.isHttpOnly()); assertTrue(c.getSecure()); assertNull(c.getDomain()); assertEquals("/", c.getPath());
            assertTrue(c.getName().startsWith("__Host-"));
            // 토큰이 리다이렉트 URL 로 새면 브라우저 히스토리·리퍼러에 남는다.
            assertFalse(r.getResponse().getRedirectedUrl().contains(c.getValue()));
        }
        assertTrue(r.getResponse().getHeaders("Set-Cookie").stream().allMatch(h -> h.contains("SameSite=Lax")));
    }

    @Test void stolenJwtAloneAndForeignBindingCannotAuthenticate() throws Exception {
        Browser a = signup("alice@example.com"), b = signup("bob@example.com");
        mvc.perform(get("/api/v1/me").cookie(a.cookies[0])).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").cookie(a.cookies[0], b.cookies[1])).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + a.cookies[0].getValue())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").param("access_token", a.cookies[0].getValue())).andExpect(status().isUnauthorized());
    }

    @Test void logoutAndLogoutAllRevokeEvenFullyCopiedCookies() throws Exception {
        Browser a = signup("alice@example.com"), other = signup("alice@example.com");
        Cookie[] stolen = a.cookies;
        // Full browser credential theft is usable until expiry/revocation; do not claim otherwise.
        mvc.perform(get("/api/v1/me").cookie(stolen)).andExpect(status().isOk());
        a.post("/api/v1/auth/logout", Map.of()).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").cookie(stolen)).andExpect(status().isUnauthorized());
        other.me().andExpect(status().isOk());
        other.post("/api/v1/auth/logout-all", Map.of()).andExpect(status().isOk());
        other.me().andExpect(status().isUnauthorized());
    }

    @Test void csrfCannotBeOmittedOrBorrowedFromAnotherBrowser() throws Exception {
        Browser a = signup("alice@example.com"), b = new Browser(); a.csrf(); b.csrf();
        mvc.perform(post("/api/v1/auth/logout").cookie(a.cookies)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").session(a.session).cookie(a.cookies).header("X-CSRF-TOKEN", b.csrf))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        a.me().andExpect(status().isOk());
    }

    @Test void hostileCorsOriginsAndWildcardConfigurationAreRejected() throws Exception {
        for (String origin : List.of("https://evil.example", "http://localhost:9999", "https://yogob.fly.dev.evil.example"))
            mvc.perform(options("/api/v1/auth/login").header("Origin", origin).header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        assertThrows(IllegalArgumentException.class, () -> new com.palsaekjo.yogobi.common.WebConfig(List.of("https://*.example.com")));
    }

    /**
     * 비밀번호 추측 제한은 D-34 로 사라졌다 — 추측할 비밀번호가 없다. IP 제한은 남고, 이제 그 제한이 걸리는
     * 곳은 **로그인 입구 자체**다. X-Forwarded-For 를 바꿔도 같은 발신지로 센다.
     */
    @Test void ipRateLimitCountsTheRealPeerAndIgnoresForwardedForSpoofing() throws Exception {
        for (int i = 0; i < 40; i++)
            mvc.perform(get("/oauth2/authorization/google").header("X-Forwarded-For", "192.0.2." + i))
                    .andExpect(status().is3xxRedirection());
        mvc.perform(get("/oauth2/authorization/google").header("X-Forwarded-For", "198.51.100.1"))
                .andExpect(status().isTooManyRequests());
    }

    @Test void googleSignupAndLoginUseSubjectAndDoNotAutoMergeEmail() throws Exception {
        Browser first = google("google-1", "alice@example.com");
        // D-36 퍼널: 로그인 성공은 실제 OIDC 왕복을 통과한 이 지점에서만 센다.
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count FROM funnel_daily WHERE kind='MEMBER_LOGIN'", Long.class));
        first.me().andExpect(jsonPath("$.data.googleLogin").value(true)).andExpect(jsonPath("$.data.localLogin").value(false));
        google("google-1", "changed@example.com").me().andExpect(jsonPath("$.data.email").value("alice@example.com"));
        Flow conflict = start(new Browser());
        callback(conflict, grant(conflict, "attacker-sub", "alice@example.com", c -> {}, RSA))
                .andExpect(redirectedUrl("https://frontend.example/#auth=account-conflict"));
        signup("local@example.com"); Flow local = start(new Browser());
        callback(local, grant(local, "new-google", "local@example.com", c -> {}, RSA))
                .andExpect(redirectedUrl("https://frontend.example/#auth=account-conflict"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class));
    }

    @ParameterizedTest @ValueSource(strings={"signature", "issuer", "audience", "expired", "nonce", "unverified", "missingEmail"})
    void maliciousGoogleIdTokensAreRejectedByRealDecoder(String attack) throws Exception {
        Flow flow = start(new Browser());
        String code = grant(flow, "google-1", "alice@example.com", c -> {
            switch (attack) {
                case "issuer" -> c.issuer("https://evil.example");
                case "audience" -> c.audience("other-app");
                case "expired" -> c.issueTime(Date.from(Instant.now().minusSeconds(1200))).expirationTime(Date.from(Instant.now().minusSeconds(600)));
                case "nonce" -> c.claim("nonce", "stolen-token-nonce");
                case "unverified" -> c.claim("email_verified", false);
                case "missingEmail" -> c.claim("email", null);
            }
        }, attack.equals("signature") ? new RSAKeyGenerator(2048).keyID("test-key").generate() : RSA);
        callback(flow, code).andExpect(redirectedUrl("https://frontend.example/#auth=failed"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_session", Integer.class));
    }

    @Test void stolenAuthorizationCodeCannotMoveBetweenBrowserStatesOrReplay() throws Exception {
        Flow a = start(new Browser()), b = start(new Browser());
        String code = grant(a, "google-1", "alice@example.com", c -> {}, RSA);
        mvc.perform(get("/login/oauth2/code/google").session(b.session()).param("state", a.query().get("state")).param("code", code))
                .andExpect(redirectedUrl("https://frontend.example/#auth=failed"));
        var result = callback(a, code).andExpect(redirectedUrl("https://frontend.example/#auth=success")).andReturn();
        assertTrue(a.session().isInvalid());
        mvc.perform(get("/login/oauth2/code/google").param("state", a.query().get("state")).param("code", code))
                .andExpect(redirectedUrl("https://frontend.example/#auth=failed"));
        assertFalse(result.getResponse().getRedirectedUrl().contains("token"));
    }

    @Test void leakedSigningKeyCannotFabricateSessionsAndDbContainsOnlyFingerprints() throws Exception {
        Browser victim = signup("alice@example.com");
        SignedJWT issued = SignedJWT.parse(victim.cookies[0].getValue());
        var claims = new JWTClaimsSet.Builder(issued.getJWTClaimsSet()).jwtID("fabricated").build();
        var forged = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        forged.sign(new MACSigner(Base64.getDecoder().decode(SECRET)));
        Cookie token = new Cookie("__Host-YGB_AUTH", forged.serialize());
        mvc.perform(get("/api/v1/me").cookie(token, victim.cookies[1])).andExpect(status().isUnauthorized());
        String stored = jdbc.queryForObject("SELECT token_hash FROM auth_session", String.class);
        assertEquals(AuthTokens.hash(victim.cookies[0].getValue()), stored);
        assertFalse(stored.contains(victim.cookies[0].getValue()));
    }

    @Test void weakOrReusedKeyFailsConfigurationAndRotationInvalidatesOldTokens() throws Exception {
        assertThrows(IllegalStateException.class, () -> new AuthTokens(jdbc, "short", "", true));
        assertThrows(IllegalStateException.class, () -> new AuthTokens(jdbc, SECRET, SECRET, true));
        Browser b = signup("alice@example.com");
        var request = new org.springframework.mock.web.MockHttpServletRequest(); request.setCookies(b.cookies);
        assertNotNull(tokens.authenticate(request));
        var rotated = new AuthTokens(jdbc, Base64.getEncoder().encodeToString(new byte[32]), "", true);
        assertNull(rotated.authenticate(request));
        assertNull(new AuthTokens(jdbc, "", "", true).authenticate(request));
    }

    @ParameterizedTest @ValueSource(strings={"issuer", "audience", "expired", "missingExpiry", "future", "subject", "signature", "algorithm"})
    void invalidMemberJwtIsRejectedEvenIfFingerprintExists(String attack) throws Exception {
        Browser b = signup("alice@example.com");
        var original = SignedJWT.parse(b.cookies[0].getValue()).getJWTClaimsSet();
        var claims = new JWTClaimsSet.Builder(original);
        switch (attack) {
            case "issuer" -> claims.issuer("other-service");
            case "audience" -> claims.audience("other-client");
            case "expired" -> claims.expirationTime(Date.from(Instant.now().minusSeconds(5)));
            case "missingExpiry" -> claims.expirationTime(null);
            case "future" -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(3600)));
            case "subject" -> claims.subject("not-a-user-id");
        }
        byte[] key = Base64.getDecoder().decode(SECRET);
        if (attack.equals("signature")) key = new byte[32];
        JWSAlgorithm algorithm = attack.equals("algorithm") ? JWSAlgorithm.HS512 : JWSAlgorithm.HS256;
        if (attack.equals("algorithm")) key = new byte[64];
        var jwt = new SignedJWT(new JWSHeader(algorithm), claims.build()); jwt.sign(new MACSigner(key));
        jdbc.update("UPDATE auth_session SET token_hash=?", AuthTokens.hash(jwt.serialize()));
        mvc.perform(get("/api/v1/me").cookie(new Cookie("__Host-YGB_AUTH", jwt.serialize()), b.cookies[1]))
                .andExpect(status().isUnauthorized());
    }

    @Test void expiredDatabaseSessionAndDuplicateCookiesAreRejected() throws Exception {
        Browser b = signup("alice@example.com");
        mvc.perform(get("/api/v1/me").cookie(b.cookies[0], b.cookies[0], b.cookies[1])).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE auth_session SET expires_at=now()-interval '1 second'");
        b.me().andExpect(status().isUnauthorized());
    }

    @Test void httpSessionCannotBypassMemberJwtAuthentication() throws Exception {
        var session = new MockHttpSession();
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("1", null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MEMBER"))));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test void interceptedCodeWithAttackerStateFailsPkce() throws Exception {
        Flow a = start(new Browser()), b = start(new Browser());
        String code = grant(a, "google-1", "alice@example.com", c -> {}, RSA);
        callback(b, code).andExpect(redirectedUrl("https://frontend.example/#auth=failed"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class));
    }

    @Test void memberSessionsAreListedWithCurrentFlagAndRevocable() throws Exception {
        Browser a = signup("alice@example.com"), b = signup("alice@example.com");
        var data = JSON.readTree(mvc.perform(get("/api/v1/me/sessions").cookie(a.cookies)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        String currentId = null, otherId = null;
        for (JsonNode s : data) { if (s.path("current").asBoolean()) currentId = s.path("id").asText(); else otherId = s.path("id").asText(); }
        assertEquals(2, data.size()); assertNotNull(currentId); assertNotNull(otherId);
        a.csrf();
        mvc.perform(delete("/api/v1/me/sessions/" + otherId).session(a.session).cookie(a.cookies).header("X-CSRF-TOKEN", a.csrf))
                .andExpect(status().isOk());
        b.me().andExpect(status().isUnauthorized());
        a.me().andExpect(status().isOk());
        a.csrf();
        mvc.perform(delete("/api/v1/me/sessions/" + currentId).session(a.session).cookie(a.cookies).header("X-CSRF-TOKEN", a.csrf))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").cookie(a.cookies)).andExpect(status().isUnauthorized());
    }
    @Test void sessionIdleTimeoutAndAbsoluteLifetimeLimitCopiedCookies() throws Exception {
        Browser b = signup("alice@example.com");
        var claims = SignedJWT.parse(b.cookies[0].getValue()).getJWTClaimsSet();
        // D-48: 절대 24시간 · 유휴 2시간. 15분·5분은 디테일 모드 입력 중에 세션을 죽였다(G-33).
        assertEquals(86_400_000, claims.getExpirationTime().getTime()-claims.getIssueTime().getTime());
        for (Cookie cookie : b.cookies) assertEquals(86_400, cookie.getMaxAge());
        jdbc.update("UPDATE auth_session SET last_seen_at=now()-interval '110 minutes'");
        b.me().andExpect(status().isOk());
        assertTrue(jdbc.queryForObject("SELECT last_seen_at>now()-interval '1 minute' FROM auth_session", Boolean.class));
        jdbc.update("UPDATE auth_session SET last_seen_at=now()-interval '121 minutes'");
        b.me().andExpect(status().isUnauthorized());
        assertTrue(tokens.sessions(Long.parseLong(claims.getSubject()), new org.springframework.mock.web.MockHttpServletRequest()).isEmpty());
    }

    @Test void anotherMemberCannotListOrRevokeVictimsSessionAndCsrfIsRequired() throws Exception {
        Browser victim = signup("alice@example.com"), attacker = signup("bob@example.com");
        String id = jdbc.queryForObject("SELECT id::text FROM auth_session WHERE token_hash=?", String.class, AuthTokens.hash(victim.cookies[0].getValue()));
        var listing = mvc.perform(get("/api/v1/me/sessions").cookie(attacker.cookies)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andReturn().getResponse().getContentAsString();
        assertFalse(listing.contains(id)); assertFalse(listing.contains("token_hash")); assertFalse(listing.contains("binding"));
        attacker.csrf();
        mvc.perform(delete("/api/v1/me/sessions/" + id).cookie(attacker.cookies).session(attacker.session).header("X-CSRF-TOKEN", attacker.csrf))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/me/sessions/" + id).cookie(victim.cookies)).andExpect(status().isForbidden());
        victim.me().andExpect(status().isOk());
        for (String path : List.of("/api/v1/catalog/reports", "/api/v1/admin/login"))
            mvc.perform(postJson(path, Map.of())).andExpect(status().isForbidden());
    }

    @Test void googleCallbackConfigurationRejectsUnreachablePathAndInjectedQuery() {
        var config = new SecurityConfig();
        for (String url : List.of("https://example.com/other", "https://example.com/login/oauth2/code/google?redirect=evil",
                "https://example.com/login/oauth2/code/google#fragment", "http://example.com/login/oauth2/code/google"))
            assertThrows(IllegalStateException.class, () -> config.googleRegistration("client", "secret", url));
        assertNotNull(config.googleRegistration("client", "secret", "https://example.com/login/oauth2/code/google"));
    }

}
