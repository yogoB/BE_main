package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.recommend.CalculatorRequest;
import com.palsaekjo.yogobi.recommend.RecommendationRequest;
import com.palsaekjo.yogobi.recommend.RecommendationService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * G-17. 해외 결제 구독은 원화로 **표시만** 하고 계산에는 넣지 않는다.
 * 금액을 지어내지 않으면서도 결과를 막지 않는지(원칙 5-①) 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "CATALOG_CSV_DIR=")
@Testcontainers
class ForeignCurrencyCatalogTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    /** 환율 배치가 실제 외부를 부르지 않도록 로컬 스텁을 세운다. 응답 본문은 테스트가 갈아끼운다. */
    static final AtomicReference<String> FX_BODY = new AtomicReference<>(
            "{\"amount\":1.0,\"base\":\"USD\",\"date\":\"2026-09-16\",\"rates\":{\"KRW\":1400.5}}");
    static final AtomicInteger FX_STATUS = new AtomicInteger(200);
    static HttpServer fxStub;

    @DynamicPropertySource static void db(DynamicPropertyRegistry r) throws IOException {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        fxStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fxStub.createContext("/latest", exchange -> {
            byte[] body = FX_BODY.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(FX_STATUS.get(), body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        fxStub.start();
        r.add("yogobi.fx.url", () -> "http://127.0.0.1:" + fxStub.getAddress().getPort() + "/latest");
    }

    @AfterAll static void stopStub() {
        if (fxStub != null) fxStub.stop(0);
    }

    @Autowired CatalogReader reader;
    @Autowired ExchangeRates exchangeRates;
    @Autowired RecommendationService recommendations;
    @Autowired JdbcTemplate jdbc;

    private long usdTierId;
    private long usdServiceId;

    @BeforeEach void seedForeignTier() {
        jdbc.update("DELETE FROM fx_rate");
        jdbc.update("""
                INSERT INTO fx_rate (base, quote, rate, rate_date, source_url)
                VALUES ('USD', 'KRW', 1359.150000, DATE '2026-09-15', 'https://example.test/latest')
                """);
        usdServiceId = jdbc.queryForObject("""
                INSERT INTO subscription_service (name, category, official_url)
                VALUES ('테스트 해외구독', 'AI', 'https://example.test/pricing')
                ON CONFLICT (name) DO UPDATE SET active = TRUE RETURNING id
                """, Long.class);
        jdbc.update("DELETE FROM subscription_tier WHERE service_id = ?", usdServiceId);
        usdTierId = jdbc.queryForObject("""
                INSERT INTO subscription_tier (service_id, name, price, currency)
                VALUES (?, 'Plus', 20, 'USD') RETURNING id
                """, Long.class, usdServiceId);
    }

    /** a·b: 표시는 표기 통화 그대로 + 원화 환산(기준일 포함). 원화 등급은 환산 칸이 비어 있다. */
    @Test void foreignTierShowsOriginalPriceAndKrwEstimate() {
        var tier = tier();
        assertThat(tier.price()).isEqualTo(20);
        assertThat(tier.currency()).isEqualTo("USD");
        assertThat(tier.krwEstimate()).isEqualTo(27183);            // i: 20 × 1359.15 내림
        assertThat(tier.krwRateDate()).hasToString("2026-09-15");

        var krwTier = reader.listServices().stream()
                .flatMap(s -> s.tiers().stream()).filter(t -> "KRW".equals(t.currency())).findFirst().orElseThrow();
        assertThat(krwTier.krwEstimate()).isNull();
        assertThat(krwTier.krwRateDate()).isNull();
    }

    /** c: 환율이 없으면 환산을 만들지 않는다 — 0원으로 적으면 실제보다 싸 보인다. */
    @Test void withoutRateNoEstimateIsInvented() {
        jdbc.update("DELETE FROM fx_rate");
        assertThat(tier().krwEstimate()).isNull();
        assertThat(tier().krwRateDate()).isNull();
    }

    /** d·e: 추천은 막히지 않고, 해외 결제는 카탈로그 결손으로 기록하지 않는다. */
    @Test void recommendationExplainsExclusionWithoutRecordingAGap() {
        jdbc.update("DELETE FROM catalog_candidate");
        var response = recommendations.recommend(new RecommendationRequest(
                new RecommendationRequest.Required(10, List.of(usdServiceId)), null));

        assertThat(response.missingInputs()).anyMatch(m -> m.impact().contains("해외 결제"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_candidate", Integer.class)).isZero();
    }

    /** f·g: 계산기는 해외 등급만 빼고 200, 없는 ID 는 그대로 400. */
    @Test void calculatorSkipsForeignTierButStillRejectsUnknownId() {
        long planId = jdbc.queryForObject("SELECT id FROM mobile_plan WHERE active ORDER BY id LIMIT 1", Long.class);
        long krwTierId = jdbc.queryForObject(
                "SELECT id FROM subscription_tier WHERE active AND currency = 'KRW' ORDER BY id LIMIT 1", Long.class);

        var response = recommendations.calculate(new CalculatorRequest(planId, List.of(krwTierId, usdTierId), null));
        assertThat(response.result()).isNotNull();
        assertThat(response.missingInputs()).anyMatch(m -> m.impact().contains("해외 결제"));

        assertThatThrownBy(() -> recommendations.calculate(
                new CalculatorRequest(planId, List.of(krwTierId, 99_999_999L), null)))
                .isInstanceOf(ApiException.class);
    }

    /** 배치는 받은 값과 기준일을 그대로 남긴다. */
    @Test void dailyRefreshStoresRateAndDate() {
        FX_STATUS.set(200);
        exchangeRates.refresh("USD", "KRW");

        var rate = exchangeRates.rate("USD", "KRW").orElseThrow();
        assertThat(rate.rate()).isEqualByComparingTo("1400.5");
        assertThat(rate.rateDate()).hasToString("2026-09-16");
        assertThat(tier().krwEstimate()).isEqualTo(28010);           // 20 × 1400.5
    }

    /** h: 호출이 실패해도 이전 값이 남는다 — 화면이 비지 않는다. */
    @Test void failedRefreshKeepsThePreviousRate() {
        FX_STATUS.set(500);
        exchangeRates.refresh("USD", "KRW");

        var rate = exchangeRates.rate("USD", "KRW").orElseThrow();
        assertThat(rate.rate()).isEqualByComparingTo("1359.15");     // @BeforeEach 가 넣은 값 그대로
        assertThat(rate.rateDate()).hasToString("2026-09-15");
        FX_STATUS.set(200);
    }

    private CatalogReader.TierView tier() {
        return reader.listServices().stream()
                .filter(s -> s.id() == usdServiceId).flatMap(s -> s.tiers().stream())
                .findFirst().orElseThrow();
    }
}
