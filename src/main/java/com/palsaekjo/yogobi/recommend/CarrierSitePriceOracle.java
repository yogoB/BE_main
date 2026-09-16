package com.palsaekjo.yogobi.recommend;

import com.fasterxml.jackson.databind.JsonNode;
import com.palsaekjo.yogobi.catalog.PriceOracle;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 통신사 공식 요금제 목록으로 금액을 대조한다(D-29의 세 번째 소스, D-35).
 *
 * <p>스마트초이스는 <b>조건별 추천</b> API 라 제안한 요금제가 추천 목록에 없으면 "확인 못 함"이 된다.
 * 알뜰폰은 특히 그렇다. 이 소스는 사업자 <b>전체 목록</b>을 받으므로 커버가 겹치지 않고,
 * 그만큼 UNVERIFIED 가 줄어든다. 공개 JSON 엔드포인트만 읽어 <b>모델 비용이 없다</b>.
 *
 * <p><b>가격은 정가를 쓴다.</b> 사업자마다 "한시 프로모션가"와 "프로모션 종료 후 정상가"를 따로 주는데,
 * 카탈로그의 {@code base_price} 는 정상가다. 프로모션가를 읽으면 멀쩡한 행이 전부 MISMATCH 가 된다.
 *
 * <p>호출은 <b>운영자의 승인 절차에서만</b> 일어난다(D-05·D-17·D-29). 사용자 요청 경로는 외부를 부르지 않는다.
 * 사업자별로 목록을 캐시한다 — 검수함을 여러 건 훑는 동안 같은 목록을 반복해 받지 않기 위해서다.
 *
 * <p>어떤 실패도 예외로 새어 나가지 않는다. 닿지 못하거나 형식이 달라지면 empty —
 * <b>"틀렸다"가 아니라 "확인 못 했다"</b>이고, 그래야 사이트가 바뀐 날 승인 절차가 멈추지 않는다.
 */
@Component
public class CarrierSitePriceOracle implements PriceOracle {
    private static final Logger log = LoggerFactory.getLogger(CarrierSitePriceOracle.class);
    /** 카탈로그는 시간 단위로 바뀌지 않는다. 길게 잡아 재조회를 줄인다. */
    private static final Duration FRESH_FOR = Duration.ofMinutes(30);
    /** docs/data.md §3 수집 준수사항. 한 사업자에 여러 번 물어야 할 때만 쓴다. */
    private static final Duration BETWEEN_REQUESTS = Duration.ofSeconds(1);

    private final RestClient client;
    private final Map<String, Snapshot> cache = new HashMap<>();
    private final Map<String, Function<CarrierSitePriceOracle, Map<String, Long>>> sources =
            new LinkedHashMap<>(Map.of(
                    "SK세븐모바일", CarrierSitePriceOracle::sk7mobile,
                    "LG헬로모바일", CarrierSitePriceOracle::lgHelloVision,
                    "KT엠모바일", CarrierSitePriceOracle::ktmMobile));

    private record Snapshot(Instant at, Map<String, Long> prices) {
    }

    public CarrierSitePriceOracle(RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.client = builder.requestFactory(factory).build();
    }

    @Override
    public String sourceName() {
        return "통신사 공식";
    }

    @Override
    public Optional<Long> officialPrice(String carrier, String planName, long dataMb, String networkType) {
        String key = carrier == null ? "" : carrier.strip();
        var source = sources.get(key);
        if (source == null || planName == null) return Optional.empty();   // 어댑터 없는 사업자
        return Optional.ofNullable(prices(key, source).get(planName.strip()));
    }

    private synchronized Map<String, Long> prices(
            String carrier, Function<CarrierSitePriceOracle, Map<String, Long>> source) {
        Snapshot cached = cache.get(carrier);
        if (cached != null && Duration.between(cached.at(), Instant.now()).compareTo(FRESH_FOR) < 0)
            return cached.prices();
        Map<String, Long> fetched;
        try {
            fetched = source.apply(this);
            log.info("{} 공식 목록 {}건 수신", carrier, fetched.size());
        } catch (RuntimeException e) {
            log.warn("{} 공식 목록 조회 실패 — 확인 못 함으로 처리 ({})", carrier, e.getClass().getSimpleName());
            fetched = Map.of();
        }
        // 빈 결과도 캐시한다. 사이트가 죽은 날 검수 건마다 다시 때리지 않는다.
        cache.put(carrier, new Snapshot(Instant.now(), fetched));
        return fetched;
    }

    /* ---- 사업자별 어댑터. 목록 화면이 호출하는 공개 엔드포인트를 그대로 쓴다. ---- */

    /** SK세븐모바일. basicAmt 는 정가 표시, basicAmt2 가 실제 판매가다. */
    private Map<String, Long> sk7mobile() {
        JsonNode body = postJson("https://www.sk7mobile.com/prod/data/searchPlanList.do", "{}");
        return collect(body == null ? null : body.path("resultList"),
                "prodNm", "basicAmt2", "basicAmt");
    }

    /** LG헬로모바일. Directmall 은 한시 프로모션가, AfterPrice 가 종료 후 정상가다. */
    private Map<String, Long> lgHelloVision() {
        JsonNode body = postForm("https://direct.lghellovision.net/fund/ajaxRateList.do", "");
        return collect(body == null ? null : body.path("list"),
                "salesName", "directPromotionAfterPrice", "directPromotionDirectmallPrice");
    }

    /**
     * KT엠모바일. 요금제가 카테고리별로 나뉘어 있어 목록을 두 번에 걸쳐 받는다 —
     * 카테고리 목록 한 번, 카테고리마다 한 번. 요청 간 1초를 지키므로 갱신에 분 단위가 걸린다.
     * 그래서 캐시가 특히 중요하다(운영자 경로이고 하루 1회 배치가 먼저 데운다).
     */
    private Map<String, Long> ktmMobile() {
        JsonNode categories = postForm("https://www.ktmmobile.com/rate/getCtgXmlAllListAjax.do",
                "rateAdsvcDivCd=RATE");
        if (categories == null || !categories.isArray()) return Map.of();
        var prices = new HashMap<String, Long>();
        boolean first = true;
        for (JsonNode category : categories) {
            String code = category.path("rateAdsvcCtgCd").asText("");
            if (code.isEmpty()) continue;
            if (!first) pause();
            first = false;
            JsonNode plans = postForm("https://www.ktmmobile.com/rate/rateContentAjax.do",
                    "rateAdsvcCtgCd=" + code);
            prices.putAll(collect(plans, "rateAdsvcNm", "mmBasAmtVatDesc", "mmBasAmtDesc"));
        }
        return Map.copyOf(prices);
    }

    /* ---- 공통 ---- */

    /** 이름 → 금액. 우선 필드가 비어 있으면 대체 필드를 쓴다. 먼저 본 이름을 유지한다. */
    private static Map<String, Long> collect(JsonNode list, String nameField, String priceField, String fallback) {
        if (list == null || !list.isArray()) return Map.of();
        var prices = new HashMap<String, Long>();
        for (JsonNode item : list) {
            String name = item.path(nameField).asText("").strip();
            Long price = won(item.path(priceField));
            if (price == null) price = won(item.path(fallback));
            if (!name.isEmpty() && price != null) prices.putIfAbsent(name, price);
        }
        return Map.copyOf(prices);
    }

    private JsonNode postJson(String url, String body) {
        return client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                .header("X-Requested-With", "XMLHttpRequest").body(body)
                .retrieve().body(JsonNode.class);
    }

    private JsonNode postForm(String url, String body) {
        return client.post().uri(url).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Requested-With", "XMLHttpRequest").body(body)
                .retrieve().body(JsonNode.class);
    }

    private static void pause() {
        try {
            Thread.sleep(BETWEEN_REQUESTS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("수집이 중단됐습니다", e);
        }
    }

    /** "33,000" 같은 표기에서 숫자만 취한다. 값이 없거나 숫자가 없으면 null. */
    static Long won(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String digits = node.asText("").replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : Long.parseLong(digits);
    }

    /** 어댑터가 있는 사업자 목록. 문서·운영자 안내가 코드와 어긋나지 않게 여기서 읽는다. */
    public List<String> supportedCarriers() {
        return sources.keySet().stream().sorted().toList();
    }
}
