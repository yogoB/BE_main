package com.palsaekjo.yogobi.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 운영 지표(D-25)는 관리 포트에만 있어야 한다.
 * 공개 포트로도 열리면 회원 수와 내부 경로 목록이 인증 없이 밖으로 나간다 — 그걸 막는 것이 이 테스트다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh", "management.server.port=0"})
@AutoConfigureObservability  // 테스트는 metrics export 가 기본 off 다 — 켜야 운영과 같은 상태가 된다
@Testcontainers
class MetricsEndpointTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TestRestTemplate rest;
    @LocalServerPort int publicPort;
    @LocalManagementPort int managementPort;

    @Test void 관리_포트는_우리_지표를_내보낸다() {
        rest.getForEntity("http://localhost:" + publicPort + "/api/v1/catalog/services", String.class);

        var response = rest.getForEntity("http://localhost:" + managementPort + "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("yogobi_members")
                .contains("yogobi_members_signed_up_24h")
                .contains("yogobi_members_with_subscription")
                .contains("yogobi_sessions_active")
                // 엔드포인트별 지연 — micrometer 가 경로 라벨까지 붙인다
                .contains("uri=\"/api/v1/catalog/services\"");
    }

    @Test void 공개_포트로는_지표도_헬스도_안_나간다() {
        for (String path : new String[] {"/actuator/prometheus", "/actuator/health", "/actuator"})
            assertThat(rest.getForEntity("http://localhost:" + publicPort + path, String.class).getStatusCode())
                    .as(path).isNotEqualTo(HttpStatus.OK);
    }
}
