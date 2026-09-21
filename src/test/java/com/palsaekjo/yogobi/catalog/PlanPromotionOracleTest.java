package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * G-70. 특가 조회 응답을 읽는 규칙(§9).
 *
 * <p><b>행 단위 실패가 이 케이스의 전부다.</b> 한 상품을 못 읽어도 200 이 오고 나머지는 돌아온다.
 * 못 읽은 번호를 "특가가 끝났다"로 읽으면 <b>멀쩡한 특가가 조용히 사라진다</b> —
 * 그래서 {@code failures} 는 별도 목록으로 오고, 호출부는 그 번호의 기존 행을 건드리지 않는다.
 *
 * <p>{@code regularPrice} 는 <b>"정가"가 아니다.</b> "N개월 이후 B원/월" 의 B 이고 13건 중 5건은
 * 지금 금액보다 싸다(장기할인). 읽는 쪽이 방향을 단정하지 않는지도 여기서 본다.
 */
class PlanPromotionOracleTest {
    private static final String PATH = "/operations/plans/promotions/check";

    /** 계약대로의 성공 본문(AI-/docs/contract.md §9). 하나는 읽히고 하나는 못 읽힌 상태다. */
    private static final String OK = """
            {"checkedAt":"2026-09-21T02:11:48.512Z",
             "promotions":[{"productId":7752,"carrier":"A모바일(에넥스텔레콤)",
                            "planName":"[Npay 5천] 10GB/100분 (6개월)","network":"LGU+",
                            "promoMonths":6,"regularPrice":13200,
                            "sourceUrl":"https://www.mvnohub.kr/product/products/7752.do",
                            "sourceHash":"3f9c","evidence":"월 19,800 원 6개월 이후 13,200 원/월"}],
             "failures":[{"productId":9999,"code":"CATALOG-SOURCE-UNAVAILABLE"}]}""";

    /** a — 읽은 것과 못 읽은 것이 <b>갈려서</b> 온다. 못 읽은 번호가 성공 목록에 섞이면 안 된다. */
    @Test void g70a_readRowsAndFailedRowsComeBackSeparately() throws Exception {
        withServer(200, OK, oracle -> {
            var check = oracle.check(List.of(7752L, 9999L));
            assertThat(check.promotions()).singleElement().satisfies(p -> {
                assertThat(p.productId()).isEqualTo(7752);
                assertThat(p.promoMonths()).isEqualTo(6);
                // 지금 금액(19,800)보다 **싸다**. 이 값은 "정가"가 아니라 N개월 이후의 금액이다.
                assertThat(p.regularPrice()).isEqualTo(13_200);
                assertThat(p.evidence()).isEqualTo("월 19,800 원 6개월 이후 13,200 원/월");
            });
            assertThat(check.failures()).singleElement().satisfies(f -> {
                assertThat(f.productId()).isEqualTo(9999);
                assertThat(f.code()).isEqualTo("CATALOG-SOURCE-UNAVAILABLE");
            });
        });
    }

    /** b — 출처 URL 에서 상품 번호를 뽑는다. 형식이 다르면 <b>빈 값</b>이다 — 번호를 지어내지 않는다. */
    @Test void g70b_productIdComesFromTheSourceUrlOrNothing() {
        assertThat(PlanPromotionOracle.productId("https://www.mvnohub.kr/product/products/7752.do"))
                .contains(7752L);
        assertThat(PlanPromotionOracle.productId("https://www.mvnohub.kr/product/list.do")).isEmpty();
        assertThat(PlanPromotionOracle.productId(null)).isEmpty();
    }

    /** c — 계약 상한을 넘겨 부르지 않는다. 30개까지다. */
    @Test void g70c_refusesBatchesOutsideTheContractRange() throws Exception {
        withServer(200, OK, oracle -> {
            assertThatThrownBy(() -> oracle.check(List.of()))
                    .isInstanceOf(PlanPromotionOracle.Unavailable.class);
            var tooMany = java.util.stream.LongStream.rangeClosed(1, PlanPromotionOracle.MAX_PRODUCTS + 1)
                    .boxed().toList();
            assertThatThrownBy(() -> oracle.check(tooMany))
                    .isInstanceOf(PlanPromotionOracle.Unavailable.class);
        });
    }

    /** d — 실패 코드는 {@code detail.code} 에 있다. §8 에서 이 자리를 놓쳐 모든 실패가 한 코드로 뭉개졌다. */
    @Test void g70d_readsTheFailureCodeFromDetail() throws Exception {
        withServer(502, """
                {"detail":{"code":"CATALOG-SOURCE-CHANGED","message":"값을 확인하지 못했습니다."}}""",
                oracle -> assertThatThrownBy(() -> oracle.check(List.of(7752L)))
                        .isInstanceOf(PlanPromotionOracle.Unavailable.class)
                        .satisfies(e -> assertThat(((PlanPromotionOracle.Unavailable) e).code())
                                .isEqualTo("CATALOG-SOURCE-CHANGED")));
    }

    /** e — 계약과 다른 모양이면 <b>조용히 넘기지 않는다.</b> 숫자 자리에 숫자가 없으면 사람이 봐야 한다. */
    @Test void g70e_aResponseThatDoesNotMatchTheContractIsLoud() throws Exception {
        withServer(200, """
                {"checkedAt":"x","promotions":[{"productId":7752,"promoMonths":"여섯","regularPrice":1}],
                 "failures":[]}""",
                oracle -> assertThatThrownBy(() -> oracle.check(List.of(7752L)))
                        .isInstanceOf(PlanPromotionOracle.Unavailable.class)
                        .satisfies(e -> assertThat(((PlanPromotionOracle.Unavailable) e).code())
                                .isEqualTo("CATALOG-SOURCE-CHANGED")));
    }

    /**
     * f — 쿨다운 안이면 <b>부르지 않는다.</b> 저쪽은 부를 때마다 원본 페이지들을 읽는다.
     * §8 과 달리 대상이 목록이라 호출 전체를 하나로 막는다.
     */
    @Test void g70f_refusesToCallAgainInsideTheCooldown() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int[] calls = {0};
        server.createContext(PATH, ex -> {
            calls[0]++;
            respond(ex, 200, OK);
        });
        server.start();
        try {
            var oracle = new PlanPromotionOracle("http://127.0.0.1:" + server.getAddress().getPort(), "", 60, 90);
            oracle.check(List.of(7752L));
            assertThatThrownBy(() -> oracle.check(List.of(7753L)))
                    .isInstanceOf(PlanPromotionOracle.Unavailable.class)
                    .satisfies(e -> assertThat(((PlanPromotionOracle.Unavailable) e).code())
                            .isEqualTo("CATALOG-SOURCE-COOLDOWN"));
            assertThat(calls[0]).isEqualTo(1);   // 두 번째는 서버에 닿지도 않는다
            oracle.clearCooldown();
            oracle.check(List.of(7753L));
            assertThat(calls[0]).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void withServer(int status, String body, Consumer<PlanPromotionOracle> test) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, ex -> respond(ex, status, body));
        server.start();
        try {
            // 쿨다운 0 — 이 테스트들은 연달아 부른다.
            test.accept(new PlanPromotionOracle("http://127.0.0.1:" + server.getAddress().getPort(), "", 0, 90));
        } finally {
            server.stop(0);
        }
    }
}
