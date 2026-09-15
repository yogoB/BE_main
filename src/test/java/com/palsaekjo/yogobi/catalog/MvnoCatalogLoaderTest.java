package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 우체국알뜰폰 응답을 카탈로그 캐시(carrier·mobile_plan)에 upsert하고, 무효행 스킵·무제한 처리·재실행 멱등을 검증. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class MvnoCatalogLoaderTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    static final String XML = """
            <retrieveAlddlChargeService><successYN>Y</successYN>
              <AlddlCharge><bizName>이야기모바일</bizName><chargeName>이야기 LTE 11GB</chargeName>
                <telecomGenerationType>LTE</telecomGenerationType><chargeAmount>19800</chargeAmount>
                <voiceAmount>무제한</voiceAmount><messageAmount>무제한</messageAmount><dataAmount>11000</dataAmount></AlddlCharge>
              <AlddlCharge><bizName>이야기모바일</bizName><chargeName>이야기 5G 무제한</chargeName>
                <telecomGenerationType>5G</telecomGenerationType><chargeAmount>39000</chargeAmount>
                <voiceAmount>300</voiceAmount><messageAmount>300</messageAmount><dataAmount>무제한</dataAmount></AlddlCharge>
              <AlddlCharge><bizName>테스트</bizName><chargeName>무료체험</chargeName>
                <telecomGenerationType>LTE</telecomGenerationType><chargeAmount>0</chargeAmount>
                <voiceAmount>0</voiceAmount><messageAmount>0</messageAmount><dataAmount>0</dataAmount></AlddlCharge>
            </retrieveAlddlChargeService>""";

    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    HttpServer server;

    @BeforeEach void clear() {
        jdbc.execute("DELETE FROM mobile_plan");
        jdbc.execute("DELETE FROM carrier");
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private MvnoCatalogLoader loaderReturning(String xml) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", ex -> {
            byte[] body = xml.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/xml;charset=UTF-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/x";
        return new MvnoCatalogLoader(new PostOfficeMvnoClient(RestClient.builder(), "testkey", url), jdbc, url);
    }

    @Test void loadsValidPlansSkipsInvalidAndIsIdempotent() throws Exception {
        var loader = loaderReturning(XML);
        var result = loader.load();

        assertThat(result.fetched()).isEqualTo(3);
        assertThat(result.loaded()).isEqualTo(2);   // 기본료 0 행은 스킵
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mobile_plan", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT carrier_type FROM carrier WHERE name='이야기모바일'", String.class)).isEqualTo("MVNO");
        // 무제한(문자) → 999999, 숫자는 그대로
        assertThat(jdbc.queryForObject("SELECT data_mb FROM mobile_plan WHERE name='이야기 LTE 11GB'", Long.class)).isEqualTo(11000L);
        assertThat(jdbc.queryForObject("SELECT voice_min FROM mobile_plan WHERE name='이야기 LTE 11GB'", Long.class)).isEqualTo(999999L);
        assertThat(jdbc.queryForObject("SELECT data_mb FROM mobile_plan WHERE name='이야기 5G 무제한'", Long.class)).isEqualTo(999999L);
        assertThat(jdbc.queryForObject("SELECT network_type FROM mobile_plan WHERE name='이야기 5G 무제한'", String.class)).isEqualTo("FIVE_G");

        loader.load(); // 재실행 멱등
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mobile_plan", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carrier WHERE name='이야기모바일'", Integer.class)).isEqualTo(1);
    }

    @Test void disabledWithoutKeyChangesNothing() {
        var loader = new MvnoCatalogLoader(new PostOfficeMvnoClient(RestClient.builder(), "", "http://127.0.0.1:1/x"), jdbc, "x");
        var result = loader.load();
        assertThat(result.fetched()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mobile_plan", Integer.class)).isZero();
    }
}
