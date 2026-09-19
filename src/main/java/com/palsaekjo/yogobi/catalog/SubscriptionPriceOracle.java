package com.palsaekjo.yogobi.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 구독 서비스의 공식 표기가를 내레이터 레포의 조회 엔드포인트로 확인한다
 * ({@code POST /operations/subscriptions/check}, AI-/docs/contract.md §8).
 *
 * <p>D-45 로 구독 대조 소스가 사라진 뒤 {@link com.palsaekjo.yogobi.admin.CatalogDailyHarvest} 의 구독 칸이
 * 비어 있었다. 저쪽은 공식 페이지를 읽어 <b>원문을 인용</b>해 줄 뿐 계산도 저장도 하지 않는다 —
 * 대조·제안·승인·반영은 전부 이쪽 몫이고, 그래야 D-18·D-28·D-43 이 그대로 산다.
 *
 * <p><b>쿨다운은 여기 있다.</b> 저쪽은 부를 때마다 원본 페이지를 읽고 캐시도 쿨다운도 없다(무상태 규칙).
 * 일 단위 배치만 보면 충분해 보이지만 운영자가 {@code /admin} 에서 수집을 손으로 연타할 수 있다 —
 * 그 경로가 곧 원본 페이지 연타다. 외부를 부르는 이 자리에서 막아야 호출자가 늘어도 같이 지켜진다.
 *
 * <p>실패는 {@link Unavailable} 하나로 나간다. 저쪽 코드 둘을 그대로 들고 온다 —
 * {@code CATALOG-SOURCE-UNAVAILABLE}(못 읽음)과 {@code CATALOG-SOURCE-CHANGED}(읽었는데 한 상품에
 * 서로 다른 월 정가가 보임). 뒤의 것은 "장애"가 아니라 <b>사람이 페이지를 봐야 한다</b>는 신호다.
 */
@Component
public class SubscriptionPriceOracle {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionPriceOracle.class);

    /** 조회 실패. {@code code} 는 내레이터가 준 것을 그대로 옮긴다 — 품질 면이 종류별로 세야 한다. */
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

    /** 공식 페이지에서 읽은 등급 한 줄. {@code evidence} 는 그 값이 찍힌 원문이다(D-43). */
    public record Offer(String tierName, long price, String currency, String billingPeriod, String evidence) { }

    /** 조회 1건. {@code sourceUrl} 은 저쪽이 실제로 읽은 페이지다 — 우리 {@code official_url} 과 대조한다. */
    public record Check(String serviceName, String sourceUrl, String checkedAt, String sourceHash,
                        List<Offer> offers) { }

    private final RestClient client;
    private final String internalToken;
    private final Duration cooldown;
    /** 서비스별 마지막 조회 시각. 같은 페이지를 연달아 읽지 않기 위한 것이라 서비스 단위다. */
    private final Map<String, Instant> lastCheck = new ConcurrentHashMap<>();

    public SubscriptionPriceOracle(@Value("${NARRATOR_URL:http://localhost:8000}") String baseUrl,
                                   @Value("${NARRATOR_INTERNAL_TOKEN:}") String internalToken,
                                   @Value("${yogobi.harvest.subscription-cooldown-minutes:360}") long cooldownMinutes) {
        this.internalToken = internalToken;
        this.cooldown = Duration.ofMinutes(cooldownMinutes);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)   // Uvicorn 은 h2c 업그레이드를 지원하지 않는다
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(30));   // 저쪽이 원본 페이지를 읽어 오는 시간이다
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 공식 표기가 조회 1건. 쿨다운 안이면 부르지 않고 {@link Unavailable} 이다 — 조용히 넘기지 않는다. */
    public Check check(String serviceName) {
        Instant last = lastCheck.get(serviceName);
        if (last != null && last.isAfter(Instant.now().minus(cooldown))) {
            throw new Unavailable("CATALOG-SOURCE-COOLDOWN",
                    serviceName + " 는 " + cooldown.toMinutes() + "분 안에 이미 조회했다");
        }
        ResponseEntity<JsonNode> response;
        try {
            response = client.post().uri("/operations/subscriptions/check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> { if (!internalToken.isBlank()) h.set(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken); })
                    .body(Map.of("serviceName", serviceName))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> { })   // 본문의 코드를 읽어야 한다
                    .toEntity(JsonNode.class);
        } catch (RestClientException e) {
            throw new Unavailable("CATALOG-SOURCE-UNAVAILABLE", serviceName + " 조회 실패: " + e.getMessage());
        } finally {
            lastCheck.put(serviceName, Instant.now());   // 실패도 호출이다 — 실패 연타도 막아야 한다
        }
        JsonNode body = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful()) {
            // 저쪽 코드를 그대로 옮긴다. 못 읽으면 "못 읽었다"로 떨어뜨린다 — 없는 코드를 지어내지 않는다.
            throw new Unavailable(code(body), serviceName + " 조회 " + response.getStatusCode().value()
                    + ": " + (body == null ? "본문 없음" : body.toString()));
        }
        if (body == null || !body.path("offers").isArray() || body.path("offers").isEmpty())
            throw new Unavailable("CATALOG-SOURCE-UNAVAILABLE", serviceName + " 응답에 offers 가 없다");

        var offers = new ArrayList<Offer>();
        for (JsonNode offer : body.path("offers")) {
            if (!offer.path("price").isIntegralNumber() || !offer.path("tierName").isTextual())
                throw new Unavailable("CATALOG-SOURCE-CHANGED", serviceName + " 응답 형식이 계약과 다르다");
            offers.add(new Offer(offer.path("tierName").asText(), offer.path("price").asLong(),
                    offer.path("currency").asText(), offer.path("billingPeriod").asText(),
                    offer.path("evidence").asText(null)));
        }
        return new Check(body.path("serviceName").asText(serviceName), body.path("sourceUrl").asText(null),
                body.path("checkedAt").asText(null), body.path("sourceHash").asText(null), List.copyOf(offers));
    }

    /** 실패 본문의 코드. 계약은 최상위 {@code code} 이고, 공통 오류 포맷({@code error.code})도 같이 본다. */
    private static String code(JsonNode body) {
        if (body == null) return "CATALOG-SOURCE-UNAVAILABLE";
        for (JsonNode at : List.of(body.path("code"), body.path("error").path("code")))
            if (at.isTextual() && !at.asText().isBlank()) return at.asText();
        return "CATALOG-SOURCE-UNAVAILABLE";
    }

    /** 테스트·운영자 재조회용. 쿨다운을 비운다 — 운영 경로에서는 부르지 않는다. */
    public void clearCooldown() {
        lastCheck.clear();
        log.info("구독 조회 쿨다운 초기화");
    }
}
