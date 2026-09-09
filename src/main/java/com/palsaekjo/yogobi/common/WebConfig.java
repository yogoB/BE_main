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
 * 인증은 Authorization 헤더(Bearer) 예정이라 allowCredentials=false. 쿠키 인증으로 가면 true + 특정 오리진으로 바꾼다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final List<String> allowedOrigins;

    public WebConfig(@Value("${yogobi.cors.allowed-origins}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
