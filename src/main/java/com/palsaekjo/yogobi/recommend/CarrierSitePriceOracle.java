package com.palsaekjo.yogobi.recommend;

import com.fasterxml.jackson.databind.JsonNode;
import com.palsaekjo.yogobi.catalog.PriceOracle;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 통신사 공식 요금제 목록으로 금액을 대조한다(D-29의 세 번째 소스).
 *
 * <p>스마트초이스는 <b>조건별 추천</b> API 라 제안한 요금제가 추천 목록에 없으면 "확인 못 함"이 된다.
 * 알뜰폰은 특히 그렇다. 이 소스는 사업자 <b>전체 목록</b>을 받으므로 커버가 겹치지 않고,
 * 그만큼 UNVERIFIED 가 줄어든다. 공개 JSON 엔드포인트만 읽어 <b>모델 비용이 없다</b>.
 *
 * <p>호출은 <b>운영자의 승인 절차에서만</b> 일어난다(D-05·D-17·D-29). 사용자 요청 경로는 외부를 부르지 않는다.
 * 한 번 받은 목록은 짧게 캐시한다 — 검수함을 여러 건 훑는 동안 같은 목록을 반복해 받지 않기 위해서다.
 *
 * <p>어떤 실패도 예외로 새어 나가지 않는다. 닿지 못하거나 형식이 달라지면 empty —
 * <b>"틀렸다"가 아니라 "확인 못 했다"</b>이고, 그래야 사이트가 바뀐 날 승인 절차가 멈추지 않는다.
 */
@Component
public class CarrierSitePriceOracle implements PriceOracle {
    private static final Logger log = LoggerFactory.getLogger(CarrierSitePriceOracle.class);
    private static final Duration FRESH_FOR = Duration.ofMinutes(10);

    /** 사업자 → 목록 엔드포인트. 어댑터를 늘리려면 여기 한 줄과 파싱 분기를 더한다. */
    private static final Map<String, String> SOURCES = Map.of(
            "SK세븐모바일", "https://www.sk7mobile.com/prod/data/searchPlanList.do");

    private final RestClient client;
    private final Map<String, Snapshot> cache = new HashMap<>();

    private record Snapshot(Instant at, Map<String, Long> prices) {
    }

    public CarrierSitePriceOracle(RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = builder.requestFactory(factory).build();
    }

    @Override
    public String sourceName() {
        return "통신사 공식";
    }

    @Override
    public Optional<Long> officialPrice(String carrier, String planName, long dataMb, String networkType) {
        String key = carrier == null ? "" : carrier.strip();
        String url = SOURCES.get(key);
        if (url == null || planName == null) return Optional.empty();   // 어댑터 없는 사업자
        return Optional.ofNullable(prices(key, url).get(planName.strip()));
    }

    private synchronized Map<String, Long> prices(String carrier, String url) {
        Snapshot cached = cache.get(carrier);
        if (cached != null && Duration.between(cached.at(), Instant.now()).compareTo(FRESH_FOR) < 0)
            return cached.prices();
        Map<String, Long> fetched = fetch(carrier, url);
        // 빈 결과도 캐시한다. 사이트가 죽은 날 검수 건마다 다시 때리지 않는다.
        cache.put(carrier, new Snapshot(Instant.now(), fetched));
        return fetched;
    }

    private Map<String, Long> fetch(String carrier, String url) {
        try {
            JsonNode body = client.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .body("{}").retrieve().body(JsonNode.class);
            JsonNode list = body == null ? null : body.path("resultList");
            if (list == null || !list.isArray()) return Map.of();
            var prices = new HashMap<String, Long>();
            for (JsonNode item : list) {
                String name = item.path("prodNm").asText("").strip();
                // basicAmt 는 정가, basicAmt2 는 실제 판매가다. 요고비는 실제 지불 금액을 다룬다.
                Long price = won(item.path("basicAmt2"));
                if (price == null) price = won(item.path("basicAmt"));
                if (!name.isEmpty() && price != null) prices.putIfAbsent(name, price);
            }
            log.info("{} 공식 목록 {}건 수신", carrier, prices.size());
            return Map.copyOf(prices);
        } catch (RuntimeException e) {
            log.warn("{} 공식 목록 조회 실패 — 확인 못 함으로 처리 ({})", carrier, e.getClass().getSimpleName());
            return Map.of();
        }
    }

    /** "33,000" 같은 표기에서 숫자만 취한다. 값이 없거나 숫자가 없으면 null. */
    static Long won(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String digits = node.asText("").replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : Long.parseLong(digits);
    }

    /** 어댑터가 있는 사업자 목록. 문서·운영자 안내가 코드와 어긋나지 않게 여기서 읽는다. */
    public static List<String> supportedCarriers() {
        return SOURCES.keySet().stream().sorted().toList();
    }
}
