package com.palsaekjo.yogobi.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * G-74. 오류는 오류대로 돌려준다. <b>실제 HTTP 로 부른다</b> — MockMvc 는 서블릿 오류 디스패치(/error)를
 * 타지 않아서, 그 디스패치를 보안 체인이 막아 모든 예외가 401 로 바뀌던 결함을 보지 못한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh")
@Testcontainers
class ErrorResponseTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TestRestTemplate rest;

    private ResponseEntity<String> post(String path, String body, MediaType type) {
        var headers = new HttpHeaders();
        headers.setContentType(type);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    /** a. 지원하지 않는 형식은 415 다 — "로그인하세요" 401 이 아니다. */
    @Test void unsupportedContentTypeIs415NotLogin() {
        var response = post("/api/v1/calculator", "planId=1", MediaType.TEXT_PLAIN);
        assertThat(response.getStatusCode().value()).isEqualTo(415);
    }

    /** b. 목록에 빈 칸이 섞이면 400 이다 — 언박싱 NPE 500 이 401 로 나가던 경로. */
    @Test void nullTierIdIs400() {
        var response = post("/api/v1/calculator", "{\"planId\":1,\"tierIds\":[1,null]}", MediaType.APPLICATION_JSON);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("YGB-REQ-001").contains("tierIds");
    }

    /** c. 추천도 같은 선에서 막는다 — 전엔 200 이면서 결손 표에 "serviceId:null" 을 남겼다. */
    @Test void nullServiceIdIs400() {
        var response = post("/api/v1/recommendations",
                "{\"required\":{\"monthlyDataGb\":10,\"wantedServiceIds\":[null]}}", MediaType.APPLICATION_JSON);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("wantedServiceIds");
    }

    /** G-82 b. 큰 목록은 압축해서 보낸다. */
    @Test void catalogIsCompressedWhenTheClientAsks() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip");
        var response = rest.exchange("/api/v1/catalog/plans", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), byte[].class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
    }

    /** d. 숫자 자리에 문자가 오면 400 이고, 어느 칸인지와 다음 행동을 말한다. */
    @Test void typeMismatchIs400WithFieldAndNextStep() {
        var response = rest.getForEntity("/api/v1/catalog/plans/abc/benefits", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("YGB-REQ-001").contains("\"field\":\"id\"").contains("다시 골라");
    }
}
