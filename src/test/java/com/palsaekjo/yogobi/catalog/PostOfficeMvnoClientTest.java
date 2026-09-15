package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 우체국알뜰폰 어댑터: XML 파싱·successYN·fail-soft·ServiceKey 전달을 로컬 스텁으로 검증(실 API·Spring 불필요). */
class PostOfficeMvnoClientTest {
    static final String OK_XML = """
            <retrieveAlddlChargeService>
              <successYN>Y</successYN><returnCode>정상</returnCode>
              <AlddlCharge>
                <telecomName>KT</telecomName><bizName>이야기모바일</bizName><chargeName>이야기 LTE 11GB</chargeName>
                <telecomGenerationType>LTE</telecomGenerationType><chargeDiv>무약정 후불</chargeDiv>
                <chargeAmount>19,800</chargeAmount><voiceAmount>무제한</voiceAmount><messageAmount>무제한</messageAmount><dataAmount>11000</dataAmount>
              </AlddlCharge>
              <AlddlCharge>
                <telecomName>SKT</telecomName><bizName>프리티</bizName><chargeName>데이터 5G 무제한</chargeName>
                <telecomGenerationType>5G</telecomGenerationType><chargeDiv>무약정 후불</chargeDiv>
                <chargeAmount>39000</chargeAmount><voiceAmount>300</voiceAmount><messageAmount>300</messageAmount><dataAmount>무제한</dataAmount>
              </AlddlCharge>
            </retrieveAlddlChargeService>""";

    @Test void parseExtractsPlanFields() {
        var plans = PostOfficeMvnoClient.parse(OK_XML);
        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).carrier()).isEqualTo("이야기모바일");
        assertThat(plans.get(0).networkType()).isEqualTo("LTE");
        assertThat(plans.get(0).baseFee()).isEqualTo("19,800");
        assertThat(plans.get(0).data()).isEqualTo("11000");
        assertThat(plans.get(1).carrier()).isEqualTo("프리티");
        assertThat(plans.get(1).networkType()).isEqualTo("5G");
    }

    @Test void nonSuccessAndMalformedYieldEmpty() {
        assertThat(PostOfficeMvnoClient.parse("<retrieveAlddlChargeService><successYN>N</successYN></retrieveAlddlChargeService>")).isEmpty();
        assertThat(PostOfficeMvnoClient.parse("not xml <<<")).isEmpty();
        assertThat(PostOfficeMvnoClient.parse("")).isEmpty();
        assertThat(PostOfficeMvnoClient.parse(null)).isEmpty();
    }

    @Test void blankKeyReturnsEmptyWithoutCall() {
        var client = new PostOfficeMvnoClient(RestClient.builder(), "  ", "http://127.0.0.1:1/x");
        assertThat(client.enabled()).isFalse();
        assertThat(client.fetchAll()).isEmpty();
    }

    @Test void liveCallSendsServiceKeyAndParses() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var query = new AtomicReference<String>();
        server.createContext("/x", ex -> {
            query.set(ex.getRequestURI().getQuery());
            byte[] body = OK_XML.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/xml;charset=UTF-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/x";
            var client = new PostOfficeMvnoClient(RestClient.builder(), "testkey", url);
            assertThat(client.fetchAll()).hasSize(2);
            assertThat(query.get()).contains("ServiceKey=testkey");
        } finally {
            server.stop(0);
        }
    }

    @Test void httpErrorIsFailSoft() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", ex -> { ex.sendResponseHeaders(500, -1); ex.close(); });
        server.start();
        try {
            var client = new PostOfficeMvnoClient(RestClient.builder(), "k", "http://127.0.0.1:" + server.getAddress().getPort() + "/x");
            assertThat(client.fetchAll()).isEmpty();
        } finally {
            server.stop(0);
        }
    }
}
