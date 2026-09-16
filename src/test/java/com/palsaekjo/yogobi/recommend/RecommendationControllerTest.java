package com.palsaekjo.yogobi.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.palsaekjo.yogobi.common.Accuracy;
import com.palsaekjo.yogobi.common.FunnelCounter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 필터 경로가 1순위 사유(reasons)를 응답에 싣는지 — AI 장애·카탈로그 결손 시 빈 목록·정상 응답. */
class RecommendationControllerTest {
    private final RecommendationService service = mock(RecommendationService.class);
    private final Narrator narrator = mock(Narrator.class);
    private final FunnelCounter funnel = mock(FunnelCounter.class);
    private final RecommendationController controller = new RecommendationController(service, narrator, funnel);

    private final CostResult best = new CostResult(42, "넷플플랜", "SKT", 55000, 68500, 13500, 162000,
            List.of(new BreakdownLine("기본료", 55000, "OFFICIAL", null)));
    private final RecommendationRequest request = new RecommendationRequest(
            new RecommendationRequest.Required(20, List.of(1L)), null);

    @Test
    void attachesFirstResultReasonsFromNarrator() {
        var missing = List.of(new MissingInput("hasFamilyBundle", "확인 필요", "마이페이지"));
        when(service.recommend(any())).thenReturn(new RecommendationResponse(Accuracy.PARTIAL, missing, List.of(best)));
        when(narrator.reasonsFor(eq(best), eq(missing))).thenReturn(List.of("넷플릭스가 포함돼요."));

        var response = controller.recommend(request, null);

        assertThat(response.data().reasons()).containsExactly("넷플릭스가 포함돼요.");
        assertThat(response.data().results()).containsExactly(best);
        verify(narrator).reasonsFor(best, missing);
    }

    @Test
    void aiUnavailableYieldsEmptyReasonsButKeepsResults() {
        when(service.recommend(any())).thenReturn(new RecommendationResponse(Accuracy.FULL, List.of(), List.of(best)));
        when(narrator.reasonsFor(any(), any())).thenReturn(List.of()); // reasonsFor 가 장애를 빈 목록으로 흡수

        var response = controller.recommend(request, null);

        assertThat(response.data().reasons()).isEmpty();
        assertThat(response.data().results()).containsExactly(best);
    }

    @Test
    void noCandidateSkipsNarratorEntirely() {
        when(service.recommend(any())).thenReturn(new RecommendationResponse(Accuracy.PARTIAL, List.of(), List.of()));

        var response = controller.recommend(request, null);

        assertThat(response.data().reasons()).isEmpty();
        assertThat(response.data().results()).isEmpty();
        verifyNoInteractions(narrator);
    }
}
