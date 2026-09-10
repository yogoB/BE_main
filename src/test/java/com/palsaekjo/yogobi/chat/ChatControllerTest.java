package com.palsaekjo.yogobi.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.palsaekjo.yogobi.common.Accuracy;
import com.palsaekjo.yogobi.common.GlobalExceptionHandler;
import com.palsaekjo.yogobi.recommend.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatControllerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final RecommendationService service = mock(RecommendationService.class);
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private HttpServer upstream;
    private MockMvc mvc;
    private int parseStatus = 200;
    private int narrateStatus = 200;
    private String parseBody = """
            {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},"confidence":0.92,
             "optional":{"currentCarrier":null,"networkType":null,"contractType":null,"hasFamilyBundle":null}}
            """;
    private final CostResult cost = new CostResult(42, "넷플플랜", "SKT", 55000, 68500, 13500, 162000,
            List.of(new BreakdownLine("기본료", 55000, "OFFICIAL", null)));
    private final RecommendationResponse recommendation = new RecommendationResponse(Accuracy.PARTIAL,
            List.of(new MissingInput("hasFamilyBundle", "확인 필요", "통신사 마이페이지")), List.of(cost));

    @BeforeEach
    void setup() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(json.readTree(exchange.getRequestBody()));
            byte[] body = (path.equals("/parse") ? parseBody :
                    "{\"message\":\"실제 내시는 금액은 월 55,000원이에요.\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(path.equals("/parse") ? parseStatus : narrateStatus, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        upstream.start();
        var ai = new AiGateway(json, "http://127.0.0.1:" + upstream.getAddress().getPort(), "test-backend-only-token");
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(ai, service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(service.recommend(any())).thenReturn(recommendation);
    }

    @AfterEach
    void stop() { upstream.stop(0); }

    @Test
    void forwardsInputsAndBackendCostsWithoutChangingThem() throws Exception {
        send().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECOMMENDED"))
                .andExpect(jsonPath("$.data.recommendation.results[0].monthlyTotal").value(55000))
                .andExpect(jsonPath("$.data.message").value("실제 내시는 금액은 월 55,000원이에요."))
                .andExpect(jsonPath("$.warnings").isEmpty());
        verify(service).recommend(new RecommendationRequest(new RecommendationRequest.Required(20, List.of(1L)),
                new RecommendationRequest.Optional(null, null, null, null)));
        assertEquals(List.of("/parse", "/narrate"), paths);
        assertEquals(List.of("Bearer test-backend-only-token", "Bearer test-backend-only-token"), authorizations);
        assertEquals(json.readTree("{\"text\":\"데이터 20기가 넷플릭스\"}"), bodies.getFirst());
        var expected = json.valueToTree(cost).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected)
                .set("missingInputs", json.valueToTree(recommendation.missingInputs()));
        assertEquals(json.readTree(json.writeValueAsString(expected)), bodies.get(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"confidence\":0.699,\"clarifyingQuestion\":\"월 999,999원 요금제 어떠세요?\"}",
            "{\"detail\":{\"code\":\"AI-PARSE-001\",\"clarifyingQuestion\":\"secret\"}}"
    })
    void clarificationDoesNotCallRecommendationOrNarration(String body) throws Exception {
        parseBody = body;
        parseStatus = body.contains("AI-PARSE-001") ? 502 : 200;
        send().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NEEDS_INPUT"))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("1GB 이상의 정수")))
                .andExpect(jsonPath("$.data.recommendation").isEmpty());
        verifyNoInteractions(service);
        assertEquals(List.of("/parse"), paths);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not JSON", "null", "{}", "{\"confidence\":\"0.92\"}",
            "{\"confidence\":1.1}",
            "{\"confidence\":0.9,\"required\":{\"monthlyDataGb\":18.4,\"wantedServiceIds\":[1]}}",
            "{\"confidence\":0.9,\"required\":{\"monthlyDataGb\":0,\"wantedServiceIds\":[1]}}",
            "{\"confidence\":0.9,\"required\":{\"monthlyDataGb\":20,\"wantedServiceIds\":[]}}",
            "{\"confidence\":0.9,\"required\":{\"monthlyDataGb\":20,\"wantedServiceIds\":[7]}}",
            "{\"confidence\":0.9,\"required\":{\"monthlyDataGb\":20,\"wantedServiceIds\":[1]},\"optional\":{\"hasFamilyBundle\":\"false\"}}"
    })
    void invalidAiOutputFallsBackWithoutGuessing(String body) throws Exception {
        parseBody = body;
        assertFilterFallback();
    }

    @Test
    void unavailableAiFallsBackAndDoesNotExposeUpstreamBody() throws Exception {
        parseStatus = 503;
        parseBody = "{\"secret\":\"private-key\"}";
        assertFilterFallback();
    }

    @Test
    void disconnectedAiFallsBack() throws Exception {
        upstream.stop(0);
        assertFilterFallback();
    }

    @Test
    void missingInternalTokenFallsBackWithoutCallingAi() throws Exception {
        var ai = new AiGateway(json, "http://127.0.0.1:" + upstream.getAddress().getPort(), "");
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(ai, service)).build();
        assertFilterFallback();
        assertTrue(paths.isEmpty());
    }

    @Test
    void narrationFailureKeepsRecommendation() throws Exception {
        narrateStatus = 503;
        send().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RECOMMENDED"))
                .andExpect(jsonPath("$.data.recommendation.results[0].monthlySavings").value(13500))
                .andExpect(jsonPath("$.warnings[0].code").value("YGB-EXT-001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"text\":0}", "{\"text\":\" \"}", "{\"text\":\"조건\",\"history\":[]}"})
    void invalidMessageDoesNotCallAi(String body) throws Exception {
        mvc.perform(post("/api/v1/chat/messages").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("YGB-REQ-001"));
        assertTrue(paths.isEmpty());
        verifyNoInteractions(service);
    }

    private org.springframework.test.web.servlet.ResultActions send() throws Exception {
        return mvc.perform(post("/api/v1/chat/messages").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer frontend-user-token")
                .content("{\"text\":\"데이터 20기가 넷플릭스\"}"));
    }

    private void assertFilterFallback() throws Exception {
        send().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FILTER_FALLBACK"))
                .andExpect(jsonPath("$.warnings[0].code").value("YGB-EXT-001"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-key"))));
        verifyNoInteractions(service);
    }
}
