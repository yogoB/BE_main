package com.palsaekjo.yogobi.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import com.palsaekjo.yogobi.recommend.RecommendationRequest;
import com.palsaekjo.yogobi.recommend.RecommendationResponse;
import com.palsaekjo.yogobi.recommend.RecommendationService;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat/messages")
public class ChatController {
    private static final String QUESTION = "추천에 사용할 월 데이터 용량을 1GB 이상의 정수로, 원하는 구독 서비스를 1개 이상 알려주시겠어요?";
    private static final String FILTER_MESSAGE = "지금은 대화로 조건을 확인하기 어려워요. 필터에서 데이터 용량과 구독 서비스를 선택해 주세요.";
    private final AiGateway ai;
    private final RecommendationService recommendations;

    public ChatController(AiGateway ai, RecommendationService recommendations) {
        this.ai = ai;
        this.recommendations = recommendations;
    }

    public enum Status { RECOMMENDED, NEEDS_INPUT, FILTER_FALLBACK }

    public record ChatResponse(Status status, String message, RecommendationResponse recommendation) {
    }

    @PostMapping
    public ApiResponse<ChatResponse> message(@RequestBody JsonNode request) {
        if (!request.isObject() || request.size() != 1 || !request.path("text").isTextual())
            throw ApiException.requiredMissing("text", "메시지 내용을 문자열로 입력해 주세요.");
        String text = request.get("text").textValue().strip();
        if (text.isBlank() || text.codePointCount(0, text.length()) > 4000)
            throw ApiException.requiredMissing("text", "메시지는 1~4,000자로 입력해 주세요.");

        // ponytail: 한 발화만 처리한다. 다중 턴이 필요하면 BE의 사용자별 이력·조건 병합을 연결한다.
        RecommendationRequest inputs;
        try {
            inputs = ai.parse(text);
        } catch (AiGateway.Unavailable e) {
            return warning(new ChatResponse(Status.FILTER_FALLBACK, FILTER_MESSAGE, null));
        }
        if (inputs == null) return ApiResponse.ok(new ChatResponse(Status.NEEDS_INPUT, QUESTION, null));

        // 필터 엔드포인트와 동일한 서비스·계산 엔진을 재사용한다.
        RecommendationResponse result = recommendations.recommend(inputs);
        try {
            String message = ai.narrate(result.results().getFirst(), result.missingInputs());
            return ApiResponse.ok(new ChatResponse(Status.RECOMMENDED, message, result));
        } catch (AiGateway.Unavailable e) {
            return warning(new ChatResponse(Status.RECOMMENDED,
                    "추천 결과는 준비됐어요. 설명을 불러오지 못해 요금제 목록과 항목별 금액을 확인해 주세요.", result));
        }
    }

    private static ApiResponse<ChatResponse> warning(ChatResponse response) {
        return new ApiResponse<>(response, List.of(new ApiResponse.Warning(
                "YGB-EXT-001", "AI 연결이 원활하지 않아 기본 화면으로 안내합니다.")));
    }
}
