package com.palsaekjo.yogobi.user;

import com.palsaekjo.yogobi.common.ApiException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AuthEmail {
    public enum Purpose { SIGNUP, RESET }
    public record Proof(String email, Long userId, Long credentialVersion) { }
    private final JdbcTemplate jdbc;
    private final AuthRateLimit limits;
    private final ObjectProvider<JavaMailSender> senders;
    private final boolean enabled;
    private final String from;
    private final String linkUrl;
    private final SecureRandom random = new SecureRandom();

    public AuthEmail(JdbcTemplate jdbc, AuthRateLimit limits, ObjectProvider<JavaMailSender> senders,
                     @Value("${yogobi.auth.email-enabled:false}") boolean enabled,
                     @Value("${AUTH_EMAIL_FROM:}") String from,
                     @Value("${AUTH_EMAIL_LINK_URL:http://localhost:8080/account.html}") String linkUrl) {
        this.jdbc = jdbc; this.limits = limits; this.senders = senders; this.enabled = enabled;
        this.from = from; this.linkUrl = linkUrl;
        var uri = java.net.URI.create(linkUrl);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost()))))
            throw new IllegalStateException("Email link requires a fixed HTTPS URL (HTTP only for loopback development)");
    }

    @Transactional
    public void request(String rawEmail, Purpose purpose) {
        String email = AuthService.email(rawEmail);
        if (!enabled || senders.getIfAvailable() == null || from.isBlank()) throw unavailable();
        limits.check("mail:" + email, 3);
        Long userId = null, version = null;
        if (purpose == Purpose.RESET) {
            var users = jdbc.query("SELECT id,credential_version FROM app_user WHERE lower(btrim(email))=? AND password_hash IS NOT NULL",
                    (rs, i) -> new Proof(email, rs.getLong(1), rs.getLong(2)), email);
            if (!users.isEmpty()) { userId = users.getFirst().userId(); version = users.getFirst().credentialVersion(); }
        }
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("DELETE FROM auth_email_token WHERE expires_at <= now()");
        jdbc.update("INSERT INTO auth_email_token(token_hash,purpose,email,user_id,credential_version,expires_at) VALUES (?,?,?,?,?,now()+interval '10 minutes')",
                AuthTokens.hash(token), purpose.name(), email, userId, version);
        // Same mail/response path for existing and unknown addresses. No account exists until SIGNUP is redeemed.
        send(email, "요고비 본인 확인", "요청한 가입 또는 비밀번호 재설정을 계속하려면 아래 링크를 열어 주세요.\n"
                + "10분 안에 한 번만 사용할 수 있습니다. 요청하지 않았다면 이 메일을 무시해 주세요.\n\n"
                + linkUrl + "#action=" + purpose.name().toLowerCase(java.util.Locale.ROOT) + "&token=" + token);
    }

    // Called inside the signup/reset transaction; DELETE RETURNING makes concurrent redemption single-use.
    Proof consume(String token, Purpose purpose) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        return jdbc.query("DELETE FROM auth_email_token WHERE token_hash=? AND purpose=? AND expires_at>now() RETURNING email,user_id,credential_version",
                (rs, i) -> new Proof(rs.getString(1), rs.getObject(2, Long.class), rs.getObject(3, Long.class)),
                AuthTokens.hash(token), purpose.name()).stream().findFirst().orElseThrow(AuthEmail::invalid);
    }

    void notifyResetAfterCommit(String email) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { send(email, "요고비 비밀번호 변경 안내", "비밀번호가 변경되었고 기존 로그인이 모두 해제되었습니다. 본인이 변경하지 않았다면 비밀번호를 다시 재설정해 주세요."); }
                catch (ApiException e) { org.slf4j.LoggerFactory.getLogger(AuthEmail.class).warn("Password reset notification delivery failed"); }
            }
        });
    }

    private void send(String email, String subject, String text) {
        if (!enabled || senders.getIfAvailable() == null || from.isBlank()) throw unavailable();
        var message = new SimpleMailMessage(); message.setFrom(from); message.setTo(email);
        message.setSubject(subject); message.setText(text);
        try { senders.getObject().send(message); }
        catch (MailException e) { throw unavailable(); }
    }

    static ApiException invalid() { return new ApiException("YGB-AUTH-LINK", 400, "확인 링크가 만료되었거나 사용할 수 없습니다. 다시 요청해 주세요.", null); }
    static ApiException unavailable() { return new ApiException("YGB-AUTH-MAIL", 503, "본인 확인 메일을 보낼 수 없습니다. 잠시 후 다시 시도해 주세요.", null); }
}
