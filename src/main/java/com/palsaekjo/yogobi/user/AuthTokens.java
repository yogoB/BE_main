package com.palsaekjo.yogobi.user;

import com.palsaekjo.yogobi.common.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class AuthTokens {
    // ponytail: no refresh token. Reauthenticate after 15 minutes; add proof-bound renewal only when longer sessions are needed.
    private static final Duration LIFETIME = Duration.ofMinutes(15);
    private static final String ISSUER = "yogobi";
    private static final String AUDIENCE = "yogobi-member";
    private final JdbcTemplate jdbc;
    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final boolean secure;
    private final SecureRandom random = new SecureRandom();

    public AuthTokens(JdbcTemplate jdbc, @Value("${JWT_SECRET:}") String secret,
                      @Value("${AI_INTERNAL_TOKEN:}") String aiToken,
                      @Value("${yogobi.auth.secure-cookies:true}") boolean secure) {
        this.jdbc = jdbc;
        this.secure = secure;
        if (secret.isBlank()) { encoder = null; decoder = null; return; }
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(secret); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("JWT_SECRET must be Base64", e); }
        if (bytes.length < 32 || secret.equals(aiToken))
            throw new IllegalStateException("JWT_SECRET needs an independent random key of at least 32 bytes");
        var key = new SecretKeySpec(bytes, "HmacSHA256");
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
    }

    public void requireConfigured() {
        if (encoder == null) throw new ApiException("YGB-AUTH-503", 503, "회원 인증 설정을 확인해 주세요.", null);
    }

    @org.springframework.transaction.annotation.Transactional
    public void issue(long userId, long expectedVersion, HttpServletRequest request, HttpServletResponse response) {
        requireConfigured();
        var versions = jdbc.query("SELECT credential_version FROM app_user WHERE id=? AND email_verified FOR UPDATE",
                (rs, i) -> rs.getLong(1), userId);
        if (versions.isEmpty() || versions.getFirst() != expectedVersion) throw AuthService.unauthorized();
        revokeCurrent(request);
        Instant now = Instant.now();
        String binding = randomValue();
        var claims = JwtClaimsSet.builder().issuer(ISSUER).subject(Long.toString(userId))
                .audience(List.of(AUDIENCE)).issuedAt(now).expiresAt(now.plus(LIFETIME)).id(randomValue()).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        jdbc.update("DELETE FROM auth_session WHERE expires_at <= now() OR last_seen_at <= now()-interval '5 minutes'");
        String agent = request.getHeader("User-Agent");
        agent = agent == null ? "Unknown" : agent.replaceAll("[\\p{Cntrl}]", "");
        jdbc.update("INSERT INTO auth_session(token_hash,user_id,binding_hash,expires_at,user_agent) VALUES (?,?,?,?,?)",
                hash(token), userId, hash(binding), Timestamp.from(now.plus(LIFETIME)), agent.substring(0, Math.min(agent.length(),200)));
        cookie(response, "AUTH", token, LIFETIME);
        cookie(response, "BINDING", binding, LIFETIME);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    public Long authenticate(HttpServletRequest request) {
        String token = value(request, "AUTH"), binding = value(request, "BINDING");
        if (decoder == null || token == null || binding == null || token.length() > 4096 || binding.length() > 128)
            return null;
        try {
            Jwt jwt = decoder.decode(token);
            if (!jwt.getAudience().equals(List.of(AUDIENCE)) || jwt.getExpiresAt() == null
                    || !jwt.getExpiresAt().isAfter(Instant.now()) || jwt.getIssuedAt() == null
                    || jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(30))) return null;
            long userId = Long.parseLong(jwt.getSubject());
            return jdbc.query("""
                    UPDATE auth_session SET last_seen_at=now()
                    WHERE token_hash=? AND binding_hash=? AND user_id=? AND expires_at>now()
                    AND last_seen_at>now()-interval '5 minutes'
                    RETURNING user_id
                    """, (rs, i) -> rs.getLong(1), hash(token), hash(binding), userId).stream().findFirst().orElse(null);
        } catch (JwtException | IllegalArgumentException e) { return null; }
    }

    public void revokeCurrent(HttpServletRequest request) {
        String token = value(request, "AUTH"), binding = value(request, "BINDING");
        if (token != null && binding != null)
            jdbc.update("DELETE FROM auth_session WHERE token_hash=? AND binding_hash=?", hash(token), hash(binding));
    }

    public void revokeAll(long userId) { jdbc.update("DELETE FROM auth_session WHERE user_id=?", userId); }

    public record Session(java.util.UUID id, Instant createdAt, Instant lastSeenAt, Instant expiresAt, String userAgent, boolean current) { }

    public List<Session> sessions(long userId, HttpServletRequest request) {
        return jdbc.query("""
                SELECT id,created_at,last_seen_at,expires_at,user_agent,token_hash=? FROM auth_session
                WHERE user_id=? AND expires_at>now() AND last_seen_at>now()-interval '5 minutes' ORDER BY created_at DESC
                """, (rs, i) -> new Session(rs.getObject(1,java.util.UUID.class),rs.getTimestamp(2).toInstant(),
                rs.getTimestamp(3).toInstant(),rs.getTimestamp(4).toInstant(),rs.getString(5),rs.getBoolean(6)),sessionHash(request),userId);
    }

    public void revokeSession(long userId, java.util.UUID sessionId) {
        if (jdbc.update("DELETE FROM auth_session WHERE id=? AND user_id=?", sessionId,userId) != 1)
            throw new ApiException("YGB-AUTH-404",404,"로그인 세션을 찾을 수 없습니다.",null);
    }

    public void clear(HttpServletResponse response) {
        cookie(response, "AUTH", "", Duration.ZERO);
        cookie(response, "BINDING", "", Duration.ZERO);
    }

    public String sessionHash(HttpServletRequest request) {
        String token = value(request, "AUTH");
        return token == null ? "" : hash(token);
    }

    private String value(HttpServletRequest request, String suffix) {
        String value = null;
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) {
            if (cookie.getName().equals(name(suffix))) {
                if (value != null) return null; // Ambiguous cookies fail closed.
                value = cookie.getValue();
            }
        }
        return value;
    }

    private String name(String suffix) { return (secure ? "__Host-" : "") + "YGB_" + suffix; }

    private void cookie(HttpServletResponse response, String suffix, String value, Duration age) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name(suffix), value).httpOnly(true)
                .secure(secure).sameSite("Lax").path("/").maxAge(age).build().toString());
    }

    private String randomValue() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
