package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 내레이터 응답을 읽는 규칙(D-60). <b>실패 코드를 제 자리에서 읽는지</b>가 이 테스트의 전부다 —
 * 처음에 최상위 {@code code} 를 봤는데 FastAPI 는 {@code {"detail": {...}}} 로 감싼다.
 * 그 탓에 모든 실패가 한 코드로 뭉개졌고, 기록은 멀쩡히 남아 아무도 모를 뻔했다.
 */
class SubscriptionPriceOracleTest {
    /** 계약대로의 성공 본문(AI-/docs/contract.md §8). */
    private static final String OK = """
            {"serviceName":"Spotify","sourceUrl":"https://www.spotify.com/kr-ko/premium/",
             "checkedAt":"2026-09-20T00:00:00Z","sourceHash":"sha256:abc",
             "offers":[{"tierName":"Premium Basic","price":8690,"currency":"KRW",
                        "billingPeriod":"MONTH","evidence":"월 8,690원"}]}""";

    @Test void readsOffersAndSourceUrl() throws Exception {
        withServer(200, OK, oracle -> {
            var check = oracle.check("Spotify");
            assertThat(check.sourceUrl()).isEqualTo("https://www.spotify.com/kr-ko/premium/");
            assertThat(check.offers()).singleElement().satisfies(offer -> {
                assertThat(offer.tierName()).isEqualTo("Premium Basic");
                assertThat(offer.price()).isEqualTo(8690);
                assertThat(offer.evidence()).isEqualTo("월 8,690원");
            });
        });
    }

    /** 502 의 코드는 {@code detail.code} 에 있다. 이걸 놓치면 두 실패가 한 코드로 뭉개진다. */
    @Test void readsTheFailureCodeFromDetail() throws Exception {
        withServer(502, """
                {"detail":{"code":"CATALOG-SOURCE-CHANGED","message":"상품별 월 정가를 확인하지 못했습니다."}}""",
                oracle -> assertThatThrownBy(() -> oracle.check("Spotify"))
                        .isInstanceOf(SubscriptionPriceOracle.Unavailable.class)
                        .satisfies(e -> assertThat(((SubscriptionPriceOracle.Unavailable) e).code())
                                .isEqualTo("CATALOG-SOURCE-CHANGED")));
    }

    /** 코드를 못 찾으면 "못 읽었다"로 떨어뜨린다 — 없는 코드를 지어내지 않는다. */
    @Test void fallsBackWhenTheBodyHasNoCode() throws Exception {
        withServer(502, "{\"oops\":true}",
                oracle -> assertThatThrownBy(() -> oracle.check("Spotify"))
                        .isInstanceOf(SubscriptionPriceOracle.Unavailable.class)
                        .satisfies(e -> assertThat(((SubscriptionPriceOracle.Unavailable) e).code())
                                .isEqualTo("CATALOG-SOURCE-UNAVAILABLE")));
    }

    /** 쿨다운 안이면 <b>부르지 않는다.</b> 저쪽은 부를 때마다 원본 페이지를 읽는다. */
    @Test void refusesToCallAgainInsideTheCooldown() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int[] calls = {0};
        server.createContext("/operations/subscriptions/check", ex -> {
            calls[0]++;
            byte[] body = OK.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            var oracle = new SubscriptionPriceOracle(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "", 60);
            oracle.check("Spotify");
            assertThatThrownBy(() -> oracle.check("Spotify"))
                    .isInstanceOf(SubscriptionPriceOracle.Unavailable.class)
                    .satisfies(e -> assertThat(((SubscriptionPriceOracle.Unavailable) e).code())
                            .isEqualTo("CATALOG-SOURCE-COOLDOWN"));
            assertThat(calls[0]).isEqualTo(1);   // 두 번째는 서버에 닿지도 않는다
            oracle.clearCooldown();
            oracle.check("Spotify");
            assertThat(calls[0]).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    private void withServer(int status, String body, ThrowingConsumer test) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/operations/subscriptions/check", ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        try {
            test.accept(new SubscriptionPriceOracle(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "", 0));
        } finally {
            server.stop(0);
        }
    }

    private interface ThrowingConsumer {
        void accept(SubscriptionPriceOracle oracle) throws Exception;
    }
}
