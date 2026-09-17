package com.palsaekjo.yogobi.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.palsaekjo.yogobi.catalog.CatalogVerifier;
import com.palsaekjo.yogobi.recommend.CostResult;
import com.palsaekjo.yogobi.recommend.MissingInput;
import com.palsaekjo.yogobi.recommend.Narrator;
import com.palsaekjo.yogobi.recommend.RecommendationRequest;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** AI 서버 호출과 응답 검증. 금액은 CostResult를 그대로 전달한다. */
@Component
public class AiGateway implements Narrator, CatalogVerifier {
    /**
     * `/narrate` 요청에 실어 보내는 계약 필드(architecture.md §3). <b>이 목록이 곧 계약이다.</b>
     *
     * <p>`CostResult` 를 통째로 직렬화하면 AI 가 쓰지 않는 내부 필드까지 나간다
     * (`priceCrossCheck` 가 실제로 그랬다). AI 는 계약 밖 필드를 422 로 거부하고 BE 는 그것을
     * {@link Unavailable} 로 삼키므로, <b>사유가 화면에서 조용히 사라지고 아무도 모른다.</b>
     * 레코드에 필드가 늘어도 여기 적지 않으면 새지 않는다.
     */
    private static final Set<String> NARRATE_FIELDS = Set.of(
            "planId", "planName", "carrier", "monthlyTotal", "baseline",
            "monthlySavings", "annualSavings", "breakdown", "missingInputs", "candidateCount");

    private final RestClient client;
    private final ObjectMapper json;
    private final String internalToken;

    public AiGateway(ObjectMapper json, @Value("${AI_SERVER_URL:http://localhost:8000}") String baseUrl,
                     @Value("${AI_INTERNAL_TOKEN:}") String internalToken) {
        this.json = json;
        this.internalToken = internalToken;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // Uvicorn은 h2c 업그레이드를 지원하지 않는다.
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(25));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** null은 추가 입력 필요. 호출 실패나 잘못된 응답은 필터 폴백 대상이다. */
    public RecommendationRequest parse(String text) {
        JsonNode result = post("/parse", Map.of("text", text));
        if (result == null) return null; // AI-PARSE-001
        requireObject(result, "required", "optional", "confidence", "clarifyingQuestion");
        JsonNode confidence = result.path("confidence");
        if (!confidence.isNumber() || !Double.isFinite(confidence.doubleValue())
                || confidence.doubleValue() < 0 || confidence.doubleValue() > 1) throw new Unavailable();
        if (confidence.doubleValue() < 0.7) return null;
        if (!absent(result.path("clarifyingQuestion"))) throw new Unavailable();

        JsonNode required = result.path("required");
        requireObject(required, "monthlyDataGb", "wantedServiceIds");
        JsonNode gb = required.path("monthlyDataGb");
        JsonNode services = required.path("wantedServiceIds");
        if (!gb.isIntegralNumber() || !gb.canConvertToInt() || gb.intValue() < 1
                || !services.isArray() || services.isEmpty() || services.size() > 6) throw new Unavailable();
        var ids = new ArrayList<Long>();
        for (JsonNode id : services) {
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() < 1 || id.longValue() > 6)
                throw new Unavailable();
            ids.add(id.longValue());
        }

        JsonNode optional = result.path("optional");
        RecommendationRequest.Optional options = null;
        if (!absent(optional)) {
            requireObject(optional, "currentCarrier", "networkType", "contractType", "hasFamilyBundle");
            for (String key : List.of("currentCarrier", "networkType", "contractType")) {
                JsonNode value = optional.path(key);
                if (!absent(value) && (!value.isTextual() || value.textValue().isBlank()
                        || value.textValue().length() > 100)) throw new Unavailable();
            }
            if (!absent(optional.path("networkType"))
                    && !Set.of("5G", "LTE", "3G").contains(optional.get("networkType").textValue()))
                throw new Unavailable();
            if (!absent(optional.path("contractType"))
                    && !Set.of("NONE", "SELECTIVE_25", "DEVICE_SUBSIDY")
                    .contains(optional.get("contractType").textValue())) throw new Unavailable();
            if (!absent(optional.path("hasFamilyBundle")) && !optional.get("hasFamilyBundle").isBoolean())
                throw new Unavailable();
            try {
                options = json.treeToValue(optional, RecommendationRequest.Optional.class);
            } catch (IOException e) {
                throw new Unavailable();
            }
        }
        return new RecommendationRequest(new RecommendationRequest.Required(gb.intValue(), ids), options);
    }

    /** AI 설명 1건. 실패는 {@link Unavailable} 로 던진다 — 챗봇은 그때 자기 문구로 대체한다. */
    public Narrator.Narration narrate(CostResult cost, List<MissingInput> missingInputs,
                                      Integer candidateCount) {
        ObjectNode request = json.valueToTree(cost);
        request.set("missingInputs", json.valueToTree(missingInputs));
        // 후보가 몇 개였는지는 CostResult 에 없다. 혜택도 할인도 없는 요금제에는
        // 이 값이 "왜 추천됐나"의 유일한 근거라 따로 싣는다.
        if (candidateCount != null && candidateCount > 0) {
            request.put("candidateCount", candidateCount);
        }
        request.retain(NARRATE_FIELDS);
        JsonNode result = post("/narrate", request);
        requireObject(result, "message", "reasons");
        JsonNode message = result.path("message");
        if (!message.isTextual() || message.textValue().isBlank() || message.textValue().length() > 50000)
            throw new Unavailable();
        return new Narrator.Narration(message.textValue(), reasons(result.path("reasons")));
    }

    /** 필터 경로용. AI 장애는 빈 설명으로 흡수한다 — 결과·계산은 영향 없다(보조 정보). */
    @Override
    public Narrator.Narration narrationFor(CostResult result, List<MissingInput> missingInputs,
                                           Integer candidateCount) {
        try {
            return narrate(result, missingInputs, candidateCount);
        } catch (Unavailable e) {
            return Narrator.Narration.none();
        }
    }

    /** reasons: 없으면 빈 목록. 있으면 배열·최대 3개·각 1~80자·개행 없음(AI 계약과 동일). 어긋나면 폐기. */
    private static List<String> reasons(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > 3) throw new Unavailable();
        var out = new ArrayList<String>();
        for (JsonNode item : node) {
            if (!item.isTextual()) throw new Unavailable();
            String text = item.textValue();
            if (text.isBlank() || text.length() > 80 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
                throw new Unavailable();
            out.add(text);
        }
        return List.copyOf(out);
    }

    /**
     * 카탈로그 상품 1건을 AI 서버에서 조회한다(D-29의 2차 더블체크). AI 는 공개 출처를 찾아 보고할 뿐이고
     * 우리 값과 맞는지 판정하는 규칙은 BE 가 갖는다(절대 원칙 2). 확인 못 하거나 응답이 계약과 어긋나면 empty.
     */
    @Override
    public java.util.Optional<Finding> lookup(String productType, String query) {
        JsonNode result;
        try {
            result = post("/catalog/candidates", Map.of("query", query, "productType", productType));
        } catch (Unavailable e) {
            return java.util.Optional.empty();   // AI 장애는 "확인 못 함"이지 "틀림"이 아니다.
        }
        if (result == null || !"CANDIDATE_FOUND".equals(result.path("status").asText())) return java.util.Optional.empty();
        JsonNode candidate = result.path("candidate");
        JsonNode price = candidate.path("monthlyPriceWon");
        JsonNode confidence = result.path("confidence");
        JsonNode source = candidate.path("sourceUrl");
        if (!price.isIntegralNumber() || price.longValue() < 0 || !confidence.isNumber()
                || !Double.isFinite(confidence.doubleValue()) || !source.isTextual()) return java.util.Optional.empty();
        return java.util.Optional.of(new Finding(price.longValue(), confidence.doubleValue(), source.textValue()));
    }

    private JsonNode post(String path, Object request) {
        if (internalToken.isEmpty() || internalToken.chars().anyMatch(c -> c <= 32 || c >= 127))
            throw new Unavailable();
        try {
            return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken)
                    .contentType(MediaType.APPLICATION_JSON).body(request)
                    .exchange((sent, response) -> {
                        int status = response.getStatusCode().value();
                        if (path.equals("/parse") && status == 502) {
                            JsonNode error = json.readTree(response.getBody());
                            if (error != null && "AI-PARSE-001".equals(error.path("detail").path("code").asText()))
                                return null;
                        }
                        if (status != 200) throw new Unavailable();
                        JsonNode body = json.readTree(response.getBody());
                        if (body == null || !body.isObject()) throw new Unavailable();
                        return body;
                    });
        } catch (RestClientException e) {
            throw new Unavailable();
        }
    }

    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private static void requireObject(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) throw new Unavailable();
        Set<String> allowed = Set.of(fields);
        node.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw new Unavailable();
        });
    }

    public static class Unavailable extends RuntimeException {
        public Unavailable() {
            super("AI 응답을 사용할 수 없습니다.");
        }
    }
}
