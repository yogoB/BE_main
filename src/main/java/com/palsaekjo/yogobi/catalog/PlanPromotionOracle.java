package com.palsaekjo.yogobi.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 알뜰폰 <b>기간 한정 특가</b>를 내레이터 레포의 조회 엔드포인트로 확인한다
 * ({@code POST /operations/plans/promotions/check}, AI-/docs/contract.md §9).
 *
 * <p>{@link SubscriptionPriceOracle}(§8)과 경계가 같다 — 저쪽은 공식 페이지를 <b>읽고 인용만</b> 하고,
 * 대조·제안·승인·반영은 전부 이쪽 몫이다. 그래야 D-18·D-28·D-43 이 그대로 산다.
 *
 * <p><b>§8 과 다른 점이 셋이다.</b>
 * <ol>
 *   <li><b>한 번에 여러 상품을 묻는다</b>(1~30개). 그래서 <b>간격은 저쪽이 지킨다</b>(1.2초) —
 *       §8 은 이쪽이 쿨다운으로 지켰다. 30개면 약 36초가 걸리므로 읽기 제한을 그보다 길게 잡는다.
 *       이 값이 짧아서 30개를 못 받는 게 첫 실패 모양이 될 자리다.</li>
 *   <li><b>실패가 행 단위다.</b> 한 상품을 못 읽어도 나머지는 200 으로 돌아온다.
 *       {@code failures} 에 오른 번호는 <b>기존 행을 건드리지 않는다</b> — 못 읽은 것과 특가가 끝난 것은
 *       다르다. 행이 사라진 것으로 보고 지우면 멀쩡한 특가가 조용히 없어진다.</li>
 *   <li><b>{@code regularPrice} 는 "정가" 가 아니다.</b> "N개월 이후 B원/월" 의 B 이고,
 *       13건 중 5건은 그 값이 지금 금액보다 <b>싸다</b>(장기할인·약정형). 이 값을 쓰는 문구는
 *       방향을 단정하면 안 된다.</li>
 * </ol>
 *
 * <p><b>쿨다운은 여기 있다.</b> 저쪽은 부를 때마다 원본을 읽고 캐시도 쿨다운도 없다(무상태 규칙).
 * 운영자가 {@code /admin} 에서 수집을 손으로 연타할 수 있고 그 경로가 곧 원본 페이지 연타다.
 * §8 과 달리 대상이 목록이므로 <b>호출 전체를 하나로</b> 막는다.
 */
@Component
public class PlanPromotionOracle {
    private static final Logger log = LoggerFactory.getLogger(PlanPromotionOracle.class);

    /** 한 호출에 담는 최대 상품 수. 계약이 정한 상한이다(§9). */
    public static final int MAX_PRODUCTS = 30;

    /** 출처 URL 에서 상품 번호를 뽑는다. `…/product/products/7752.do` 의 7752. */
    private static final Pattern PRODUCT_ID = Pattern.compile("/products/(\\d+)\\.do");

    /** 조회 실패(호출 전체). 행 단위 실패는 {@link Check#failures()} 로 온다. */
    public static class Unavailable extends RuntimeException {
        private final String code;

        public Unavailable(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * 공식 페이지에서 읽은 특가 1건.
     *
     * @param regularPrice {@code N개월 이후 B원/월} 의 B. <b>오를 수도 내릴 수도 있다</b>
     * @param evidence     그 값이 찍힌 원문(D-43)
     */
    public record Promotion(long productId, String carrier, String planName, int promoMonths,
                            long regularPrice, String sourceUrl, String evidence) { }

    /** 못 읽은 상품 하나. <b>이 번호의 기존 행은 건드리지 않는다.</b> */
    public record Failure(long productId, String code) { }

    public record Check(String checkedAt, List<Promotion> promotions, List<Failure> failures) { }

    private final RestClient client;
    private final String internalToken;
    private final Duration cooldown;
    /** 마지막 조회 시각. 대상이 목록이라 호출 전체를 하나로 막는다(§8 은 서비스별이었다). */
    private final AtomicReference<Instant> lastCheck = new AtomicReference<>();

    public PlanPromotionOracle(@Value("${NARRATOR_URL:http://localhost:8000}") String baseUrl,
                               @Value("${NARRATOR_INTERNAL_TOKEN:}") String internalToken,
                               @Value("${yogobi.harvest.promotion-cooldown-minutes:360}") long cooldownMinutes,
                               @Value("${yogobi.harvest.promotion-read-timeout-seconds:90}") long readTimeoutSeconds) {
        this.internalToken = internalToken;
        this.cooldown = Duration.ofMinutes(cooldownMinutes);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
        // 저쪽이 상품마다 1.2초를 쉬며 읽는다 — 30개면 36초다. §8 의 30초를 그대로 쓰면 못 받는다.
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 출처 URL 에서 상품 번호. 형식이 다르면 빈 값이다 — 우리가 번호를 지어내지 않는다. */
    public static java.util.Optional<Long> productId(String sourceUrl) {
        if (sourceUrl == null) {
            return java.util.Optional.empty();
        }
        var found = PRODUCT_ID.matcher(sourceUrl);
        return found.find() ? java.util.Optional.of(Long.parseLong(found.group(1))) : java.util.Optional.empty();
    }

    /**
     * 특가 조회. 쿨다운 안이면 부르지 않고 {@link Unavailable} 이다 — 조용히 넘기지 않는다.
     *
     * @param productIds 1~{@value #MAX_PRODUCTS} 개. 넘으면 호출부가 나눠서 부른다
     */
    public Check check(List<Long> productIds) {
        if (productIds.isEmpty() || productIds.size() > MAX_PRODUCTS) {
            throw new Unavailable("CATALOG-SOURCE-UNAVAILABLE",
                    "상품 번호는 1~" + MAX_PRODUCTS + "개여야 한다: " + productIds.size() + "개");
        }
        Instant last = lastCheck.get();
        if (last != null && last.isAfter(Instant.now().minus(cooldown))) {
            throw new Unavailable("CATALOG-SOURCE-COOLDOWN",
                    "특가 조회는 " + cooldown.toMinutes() + "분 안에 이미 했다");
        }
        ResponseEntity<JsonNode> response;
        try {
            response = client.post().uri("/operations/plans/promotions/check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> { if (!internalToken.isBlank()) h.set(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken); })
                    .body(Map.of("productIds", productIds))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> { })
                    .toEntity(JsonNode.class);
        } catch (RestClientException e) {
            throw new Unavailable("CATALOG-SOURCE-UNAVAILABLE", "특가 조회 실패: " + e.getMessage());
        } finally {
            lastCheck.set(Instant.now());   // 실패도 호출이다 — 실패 연타도 막아야 한다
        }
        JsonNode body = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new Unavailable(code(body), "특가 조회 " + response.getStatusCode().value()
                    + ": " + (body == null ? "본문 없음" : body.toString()));
        }
        if (body == null || !body.path("promotions").isArray()) {
            throw new Unavailable("CATALOG-SOURCE-CHANGED", "특가 조회 응답에 promotions 배열이 없다");
        }

        var promotions = new ArrayList<Promotion>();
        for (JsonNode row : body.path("promotions")) {
            if (!row.path("productId").isIntegralNumber() || !row.path("promoMonths").isIntegralNumber()
                    || !row.path("regularPrice").isIntegralNumber()) {
                throw new Unavailable("CATALOG-SOURCE-CHANGED", "특가 조회 응답 형식이 계약과 다르다: " + row);
            }
            promotions.add(new Promotion(row.path("productId").asLong(), row.path("carrier").asText(null),
                    row.path("planName").asText(null), row.path("promoMonths").asInt(),
                    row.path("regularPrice").asLong(), row.path("sourceUrl").asText(null),
                    row.path("evidence").asText(null)));
        }
        var failures = new ArrayList<Failure>();
        for (JsonNode row : body.path("failures")) {
            failures.add(new Failure(row.path("productId").asLong(), row.path("code").asText("CATALOG-SOURCE-UNAVAILABLE")));
        }
        return new Check(body.path("checkedAt").asText(null), List.copyOf(promotions), List.copyOf(failures));
    }

    /** 실패 본문의 코드. FastAPI 가 {@code detail} 로 감싼다 — §8 에서 이 자리를 잘못 봐 모든 실패가 한 코드로 뭉개졌다. */
    private static String code(JsonNode body) {
        if (body == null) return "CATALOG-SOURCE-UNAVAILABLE";
        JsonNode code = body.path("detail").path("code");
        if (code.isTextual() && !code.asText().isBlank()) return code.asText();
        log.warn("특가 조회 실패 본문에서 코드를 못 찾았다 — 계약이 바뀌었는지 확인하라: {}", body);
        return "CATALOG-SOURCE-UNAVAILABLE";
    }

    /** 테스트·운영자 재조회용. 운영 경로에서는 부르지 않는다. */
    public void clearCooldown() {
        lastCheck.set(null);
        log.info("특가 조회 쿨다운 초기화");
    }
}
