package com.palsaekjo.yogobi.recommend;

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

/** 격자 스윕이 Open API를 넓게 훑어 dedup 업서트하고, 비활성·호출 실패에도 fail-soft인지 검증(로컬 스텁 서버). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class SmartChoiceSweepServiceTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    static final String OK_XML = """
            <openAPI><resultCode>100</resultCode>
              <item><rn>1</rn><v_tel>SKT</v_tel><v_plan_name>요고1</v_plan_name>
                <v_plan_price>55000</v_plan_price><v_dis_price>41250</v_dis_price><v_display_data>무제한</v_display_data></item>
              <item><rn>2</rn><v_tel>KT</v_tel><v_plan_name>요고2</v_plan_name>
                <v_plan_price>39000</v_plan_price><v_dis_price>29250</v_dis_price><v_display_data>11GB</v_display_data></item>
            </openAPI>""";

    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    HttpServer server;

    @BeforeEach void clear() { jdbc.execute("TRUNCATE smartchoice_plan_snapshot"); }
    @AfterEach void stop() { if (server != null) server.stop(0); }

    private SmartChoiceSweepService sweepAgainst(int status, String xml) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openAPI.xml", ex -> {
            if (status != 200) { ex.sendResponseHeaders(status, -1); ex.close(); return; }
            byte[] body = xml.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/xml;charset=UTF-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/openAPI.xml";
        return new SmartChoiceSweepService(new SmartChoiceClient(RestClient.builder(), "testkey", url), jdbc, url);
    }

    @Test void sweepUpsertsDedupedLiveSnapshot() throws Exception {
        var sweep = sweepAgainst(200, OK_XML);
        var result = sweep.sweep();

        int expectedCalls = SmartChoiceSweepService.TYPES.length * SmartChoiceSweepService.DIS.length * SmartChoiceSweepService.DATA_MB.length;
        int expectedRows = SmartChoiceSweepService.TYPES.length * SmartChoiceSweepService.DIS.length * 2; // 2 distinct plans, deduped per (type,dis)
        assertThat(result.enabled()).isTrue();
        assertThat(result.calls()).isEqualTo(expectedCalls);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM smartchoice_plan_snapshot", Integer.class)).isEqualTo(expectedRows);
        assertThat(jdbc.queryForObject("SELECT plan_price FROM smartchoice_plan_snapshot WHERE carrier='SKT' AND network_type='5G' AND contract_months=24", Long.class)).isEqualTo(55000L);

        sweep.sweep(); // 재실행해도 dedup — 행 수 불변
        assertThat(jdbc.queryForObject("SELECT count(*) FROM smartchoice_plan_snapshot", Integer.class)).isEqualTo(expectedRows);
    }

    @Test void disabledWithoutKey() {
        var sweep = new SmartChoiceSweepService(
                new SmartChoiceClient(RestClient.builder(), "", "http://127.0.0.1:1/openAPI.xml"), jdbc, "x");
        var result = sweep.sweep();
        assertThat(result.enabled()).isFalse();
        assertThat(result.calls()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM smartchoice_plan_snapshot", Integer.class)).isZero();
    }

    @Test void failSoftOnHttpError() throws Exception {
        var sweep = sweepAgainst(500, "");
        var result = sweep.sweep();
        assertThat(result.enabled()).isTrue();
        assertThat(result.rowsUpserted()).isZero(); // 모든 호출 실패해도 예외 없이 진행
        assertThat(jdbc.queryForObject("SELECT count(*) FROM smartchoice_plan_snapshot", Integer.class)).isZero();
    }
}
