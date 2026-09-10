package com.palsaekjo.yogobi.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.palsaekjo.yogobi.recommend.CostResult;
import com.palsaekjo.yogobi.recommend.MissingInput;
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
public class AiGateway {
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

    public String narrate(CostResult cost, List<MissingInput> missingInputs) {
        ObjectNode request = json.valueToTree(cost);
        request.set("missingInputs", json.valueToTree(missingInputs));
        JsonNode result = post("/narrate", request);
        requireObject(result, "message");
        JsonNode message = result.path("message");
        if (!message.isTextual() || message.textValue().isBlank() || message.textValue().length() > 50000)
            throw new Unavailable();
        return message.textValue();
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
