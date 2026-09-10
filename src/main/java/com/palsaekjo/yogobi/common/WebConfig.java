package com.palsaekjo.yogobi.common;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS. 프론트가 다른 오리진에서 API를 호출하므로 브라우저 차단을 풀어준다.
 * 허용 오리진은 `yogobi.cors.allowed-origins`(env `YOGOBI_CORS_ALLOWED_ORIGINS`)로 주입 —
 * 로컬 dev 기본값 + 프론트 배포 도메인은 배포 시 secret 으로 추가하면 코드 수정이 필요 없다.
 * 회원 쿠키를 사용하므로 정확한 오리진만 허용한다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final List<String> allowedOrigins;

    public WebConfig(@Value("${yogobi.cors.allowed-origins}") List<String> allowedOrigins) {
        for (String origin : allowedOrigins) {
            var uri = java.net.URI.create(origin);
            if (origin.contains("*") || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getFragment() != null || !uri.getPath().isEmpty()
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                    && List.of("localhost", "127.0.0.1").contains(uri.getHost()))))
                throw new IllegalArgumentException("CORS needs exact HTTPS origins (HTTP only for loopback development)");
        }
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "X-CSRF-TOKEN")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
