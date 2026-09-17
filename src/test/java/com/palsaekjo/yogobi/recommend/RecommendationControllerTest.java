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

/** 필터 경로가 1순위 설명(message·reasons)을 응답에 싣는지 — AI 장애·카탈로그 결손 시 빈 설명·정상 응답. */
class RecommendationControllerTest {
    private final RecommendationService service = mock(RecommendationService.class);
    private final Narrator narrator = mock(Narrator.class);
    private final FunnelCounter funnel = mock(FunnelCounter.class);
    private final RecommendationController controller = new RecommendationController(service, narrator, funnel);

    private final CostResult best = new CostResult(42, "넷플플랜", "SKT", 55000, 68500, 13500, 162000,
            List.of(new BreakdownLine("기본료", 55000, "OFFICIAL", null)));
    private final RecommendationRequest request = new RecommendationRequest(
            new RecommendationRequest.Required(20, List.of(1L), null), null);

    @Test
    void attachesFirstResultNarrationFromNarrator() {
        var missing = List.of(new MissingInput("hasFamilyBundle", "확인 필요", "마이페이지"));
        when(service.recommend(any()))
                .thenReturn(new RecommendationResponse(Accuracy.PARTIAL, missing, List.of(best), 127));
        when(narrator.narrationFor(eq(best), eq(missing), eq(127)))
                .thenReturn(new Narrator.Narration("월 13,500원 절약할 수 있어요.", List.of("넷플릭스가 포함돼요.")));

        var response = controller.recommend(request, null);

        assertThat(response.data().reasons()).containsExactly("넷플릭스가 포함돼요.");
        assertThat(response.data().message()).isEqualTo("월 13,500원 절약할 수 있어요.");
        assertThat(response.data().results()).containsExactly(best);
        assertThat(response.data().candidateCount()).isEqualTo(127);
        // 후보 수는 CostResult 에 없다. 혜택도 할인도 없는 요금제에는 이게 유일한 근거라 따로 넘긴다.
        verify(narrator).narrationFor(best, missing, 127);
    }

    @Test
    void aiUnavailableYieldsEmptyNarrationButKeepsResults() {
        when(service.recommend(any()))
                .thenReturn(new RecommendationResponse(Accuracy.FULL, List.of(), List.of(best), 5));
        // narrationFor 가 장애를 빈 설명으로 흡수한다.
        when(narrator.narrationFor(any(), any(), any())).thenReturn(Narrator.Narration.none());

        var response = controller.recommend(request, null);

        assertThat(response.data().reasons()).isEmpty();
        assertThat(response.data().message()).isNull();
        assertThat(response.data().results()).containsExactly(best);
    }

    @Test
    void noCandidateSkipsNarratorEntirely() {
        when(service.recommend(any())).thenReturn(new RecommendationResponse(Accuracy.PARTIAL, List.of(), List.of()));

        var response = controller.recommend(request, null);

        assertThat(response.data().reasons()).isEmpty();
        assertThat(response.data().message()).isNull();
        assertThat(response.data().results()).isEmpty();
        assertThat(response.data().candidateCount()).isNull();
        verifyNoInteractions(narrator);
    }
}
