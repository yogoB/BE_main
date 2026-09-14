package com.palsaekjo.yogobi.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 스마트초이스 어댑터: XML 파싱·정렬·fail-soft·요청 파라미터를 로컬 스텁 서버로 검증(실제 API·Spring 불필요). */
class SmartChoiceClientTest {
    static final String OK_XML = """
            <openAPI>
              <resultCode>100</resultCode>
              <item><rn>2</rn><v_tel>KT</v_tel><v_plan_name>요고2</v_plan_name>
                <v_plan_price>39,000</v_plan_price><v_dis_price>29,250</v_dis_price><v_display_data>11GB</v_display_data></item>
              <item><rn>1</rn><v_tel>SKT</v_tel><v_plan_name>요고1</v_plan_name>
                <v_plan_price>55000</v_plan_price><v_dis_price>41250</v_dis_price><v_display_data>무제한</v_display_data></item>
            </openAPI>""";

    @Test void parseExtractsAndSortsByRankAndStripsSeparators() {
        var recs = SmartChoiceClient.parse(OK_XML);
        assertThat(recs).hasSize(2);
        assertThat(recs.get(0).rank()).isEqualTo(1);
        assertThat(recs.get(0).carrier()).isEqualTo("SKT");
        assertThat(recs.get(0).planPrice()).isEqualTo(55000);
        assertThat(recs.get(1).carrier()).isEqualTo("KT");
        assertThat(recs.get(1).planPrice()).isEqualTo(39000);       // "39,000" → 39000
        assertThat(recs.get(1).discountedPrice()).isEqualTo(29250);
    }

    @Test void nonSuccessCodeAndMalformedYieldEmpty() {
        assertThat(SmartChoiceClient.parse("<openAPI><resultCode>500</resultCode></openAPI>")).isEmpty();
        assertThat(SmartChoiceClient.parse("not xml <<<")).isEmpty();
        assertThat(SmartChoiceClient.parse("")).isEmpty();
        assertThat(SmartChoiceClient.parse(null)).isEmpty();
    }

    @Test void blankKeyReturnsEmptyWithoutCall() {
        var client = new SmartChoiceClient(RestClient.builder(), "  ", "http://127.0.0.1:1/openAPI.xml");
        assertThat(client.enabled()).isFalse();
        assertThat(client.recommend(3000, 100, 50, 20, 3, 24)).isEmpty(); // 미도달 포트여도 호출 자체를 안 함
    }

    @Test void liveCallSendsGridParamsAndParses() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var query = new AtomicReference<String>();
        server.createContext("/openAPI.xml", ex -> {
            query.set(ex.getRequestURI().getQuery());
            byte[] body = OK_XML.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/xml;charset=UTF-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/openAPI.xml";
            var client = new SmartChoiceClient(RestClient.builder(), "testkey", url);
            assertThat(client.recommend(3000, 999999, 50, 20, 6, 24)).hasSize(2);
            assertThat(query.get()).contains("authkey=testkey").contains("data=3000")
                    .contains("type=6").contains("dis=24").contains("voice=999999").contains("age=20");
        } finally {
            server.stop(0);
        }
    }

    @Test void unreachableEndpointThrowsUnreachable() {
        // 연결 거부/타임아웃 = 도달 실패 → Unreachable (스윕이 도달성 가드로 잡음). HTTP 오류(fail-soft)와 구분.
        var client = new SmartChoiceClient(RestClient.builder(), "k", "http://127.0.0.1:1/openAPI.xml");
        assertThatThrownBy(() -> client.recommend(3000, 100, 50, 20, 3, 24))
                .isInstanceOf(SmartChoiceClient.Unreachable.class);
    }

    @Test void httpErrorIsFailSoft() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openAPI.xml", ex -> { ex.sendResponseHeaders(500, -1); ex.close(); });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/openAPI.xml";
            var client = new SmartChoiceClient(RestClient.builder(), "k", url);
            assertThat(client.recommend(3000, 100, 50, 20, 3, 24)).isEmpty();
        } finally {
            server.stop(0);
        }
    }
}
