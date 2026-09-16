package com.palsaekjo.yogobi.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 해외 결제 구독을 원화로 **표시**하기 위한 환율. 사용자 결정 2026-09-16.
 *
 * <p>하루 1회 배치로만 갱신하고 값은 {@code fx_rate} 에 남는다. 요청 경로에서는 외부를 호출하지 않으며
 * (D-05 유지) 화면은 항상 DB 의 마지막 값을 읽는다. 호출이 실패하면 이전 값이 그대로 남는다(fail-soft).
 *
 * <p>환산값은 {@code ESTIMATED} 다 — 계산·추천 순위에는 쓰지 않는다(D-17). 사용자가 실제 결제액을
 * 확인해 넣으면 그 값이 {@code USER_PROVIDED} 로 계산에 들어간다.
 */
@Component
public class ExchangeRates {
    private static final Logger log = LoggerFactory.getLogger(ExchangeRates.class);

    /** 환율 한 건. 기준일과 출처를 함께 들고 다닌다 — 금액에는 출처가 붙는다(절대 원칙 4). */
    public record Rate(String base, String quote, BigDecimal rate, LocalDate rateDate, String sourceUrl) {
    }

    private final JdbcTemplate jdbc;
    private final RestClient client;
    private final String url;

    public ExchangeRates(JdbcTemplate jdbc, RestClient.Builder builder,
                         @Value("${yogobi.fx.url:https://api.frankfurter.dev/v1/latest}") String url) {
        this.jdbc = jdbc;
        this.url = url;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = builder.requestFactory(factory).build();
    }

    /** 화면이 읽는 값. 없으면 빈 값이며 그때는 원화 환산을 아예 표시하지 않는다(0원으로 적지 않는다). */
    public Optional<Rate> rate(String base, String quote) {
        return jdbc.query("SELECT rate, rate_date, source_url FROM fx_rate WHERE base = ? AND quote = ?",
                (rs, i) -> new Rate(base, quote, rs.getBigDecimal("rate"),
                        rs.getDate("rate_date").toLocalDate(), rs.getString("source_url")),
                base, quote).stream().findFirst();
    }

    /** 표시용 원화 환산. 원 단위 내림 — 실제보다 크게 보이지 않게 한다. */
    public static long toKrw(long amount, Rate rate) {
        return BigDecimal.valueOf(amount).multiply(rate.rate()).setScale(0, RoundingMode.FLOOR).longValue();
    }

    /**
     * 하루 1회 갱신. ECB 참고환율은 CET 16:00 무렵 고시되므로 한국 시간 아침에 받는다.
     * 실패는 경고만 남기고 넘어간다 — 화면은 이전 값으로 계속 동작한다.
     */
    @Scheduled(cron = "${yogobi.fx.cron:0 15 9 * * *}", zone = "Asia/Seoul")
    public void refresh() {
        refresh("USD", "KRW");
    }

    void refresh(String base, String quote) {
        try {
            Map<?, ?> body = client.get()
                    .uri(url + "?base={base}&symbols={quote}", base, quote)
                    .retrieve().body(Map.class);
            BigDecimal value = value(body, quote);
            LocalDate date = LocalDate.parse(String.valueOf(body.get("date")));
            if (value == null || value.signum() <= 0) {
                log.warn("환율 응답에 {} 값이 없어 이전 값을 유지합니다", quote);
                return;
            }
            jdbc.update("""
                    INSERT INTO fx_rate (base, quote, rate, rate_date, source_url, fetched_at)
                    VALUES (?, ?, ?, ?, ?, now())
                    ON CONFLICT (base, quote) DO UPDATE SET
                        rate = EXCLUDED.rate, rate_date = EXCLUDED.rate_date,
                        source_url = EXCLUDED.source_url, fetched_at = EXCLUDED.fetched_at
                    """, base, quote, value, java.sql.Date.valueOf(date), url);
            log.info("환율 갱신 {}/{} = {} (기준일 {})", base, quote, value, date);
        } catch (RuntimeException e) {
            // 네트워크·형식 문제는 표시 품질 문제일 뿐 서비스 장애가 아니다. 이전 값을 유지한다.
            log.warn("환율 갱신 실패 — 이전 값을 유지합니다: {}", e.getClass().getSimpleName());
        }
    }

    /** {"amount":1.0,"base":"USD","date":"2026-09-15","rates":{"KRW":1359.15}} 에서 환율만 꺼낸다. */
    private static BigDecimal value(Map<?, ?> body, String quote) {
        if (body == null || !(body.get("rates") instanceof Map<?, ?> rates)) {
            return null;
        }
        return rates.get(quote) instanceof Number number ? new BigDecimal(number.toString()) : null;
    }
}
