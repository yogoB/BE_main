package com.palsaekjo.yogobi.recommend;

import com.fasterxml.jackson.databind.JsonNode;
import com.palsaekjo.yogobi.detection.DetectionNarrator;
import com.palsaekjo.yogobi.detection.domain.DetectionFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
public class NarratorClient implements Narrator, DetectionNarrator {
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

    public NarratorClient(ObjectMapper json, @Value("${NARRATOR_URL:http://localhost:8000}") String baseUrl,
                     @Value("${NARRATOR_INTERNAL_TOKEN:}") String internalToken) {
        this.json = json;
        this.internalToken = internalToken;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // Uvicorn은 h2c 업그레이드를 지원하지 않는다.
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(25));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
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
        return new Narrator.Narration(message.textValue(), reasons(result.path("reasons")),
                lines(result.path("notices"), 10, 300));
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

    /** reasons: 없으면 빈 목록. 있으면 배열·최대 3개·각 1~80자·개행 없음(내레이터 계약과 동일). */
    private static List<String> reasons(JsonNode node) {
        return lines(node, 3, 80);
    }

    /**
     * 문자열 배열을 계약대로 검증해 옮긴다. 어긋나면 폐기한다 —
     * 화면은 이 값을 그대로 렌더링하므로 길이·개행을 여기서 막지 않으면 레이아웃이 깨진다.
     */
    private static List<String> lines(JsonNode node, int maxCount, int maxLength) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > maxCount) throw new Unavailable();
        var out = new ArrayList<String>();
        for (JsonNode item : node) {
            if (!item.isTextual()) throw new Unavailable();
            String text = item.textValue();
            if (text.isBlank() || text.length() > maxLength
                    || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
                throw new Unavailable();
            out.add(text);
        }
        return List.copyOf(out);
    }

    /**
     * 탐지 설명(D-46). 내레이터가 닿지 않으면 BE 가 만든 최소 설명으로 떨어진다 —
     * 문구가 없을 뿐 금액과 대상은 그대로 보인다. 화면이 통째로 비는 것보다 낫다.
     */
    @Override
    public DetectionNarrator.Explanation explain(List<DetectionFinding> findings, List<String> targetNames) {
        if (findings.isEmpty()) return new DetectionNarrator.Explanation(List.of(), "중복으로 새는 금액이 없어요.");
        var items = json.createArrayNode();
        for (int i = 0; i < findings.size(); i++) {
            DetectionFinding finding = findings.get(i);
            items.addObject()
                    .put("rule", finding.rule().name())
                    .put("targetName", targetNames.get(i))
                    .put("wastedAmount", finding.wastedAmount())
                    .put("provenance", finding.provenance().name());
        }
        try {
            JsonNode result = post("/narrate/detections", json.createObjectNode().set("findings", items));
            requireObject(result, "lines", "summary");
            JsonNode lines = result.path("lines");
            if (!lines.isArray() || lines.size() != findings.size()) throw new Unavailable();
            var out = new ArrayList<DetectionNarrator.Explained>();
            for (JsonNode line : lines) {
                out.add(new DetectionNarrator.Explained(
                        text(line, "title", 200), text(line, "target", 200),
                        text(line, "amount", 200), optional(line, "how", 200)));
            }
            return new DetectionNarrator.Explanation(List.copyOf(out), optional(result, "summary", 300));
        } catch (Unavailable e) {
            return DetectionNarrator.fallback(findings, targetNames);
        }
    }

    private static String text(JsonNode node, String field, int maxLength) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maxLength
                || value.textValue().indexOf('\n') >= 0) throw new Unavailable();
        return value.textValue();
    }

    private static String optional(JsonNode node, String field, int maxLength) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return "";
        if (!value.isTextual() || value.textValue().length() > maxLength
                || value.textValue().indexOf('\n') >= 0) throw new Unavailable();
        return value.textValue();
    }

    private JsonNode post(String path, Object request) {
        if (internalToken.isEmpty() || internalToken.chars().anyMatch(c -> c <= 32 || c >= 127))
            throw new Unavailable();
        try {
            return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken)
                    .contentType(MediaType.APPLICATION_JSON).body(request)
                    .exchange((sent, response) -> {
                        if (response.getStatusCode().value() != 200) throw new Unavailable();
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
