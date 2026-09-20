package com.palsaekjo.yogobi.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.palsaekjo.yogobi.common.Accuracy;
import com.palsaekjo.yogobi.common.FunnelCounter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 결과와 설명은 따로 간다(D-50). 추천은 내레이터를 부르지 않고, 설명은 같은 본문으로 /narrate 가 준다.
 * 하루에 설명 경로 사고가 넷이었고 그때마다 결과 화면이 같이 흔들렸다.
 */
class RecommendationControllerTest {
    private final RecommendationService service = mock(RecommendationService.class);
    private final Narrator narrator = mock(Narrator.class);
    private final FunnelCounter funnel = mock(FunnelCounter.class);
    private final RecommendationStats stats = mock(RecommendationStats.class);
    private final MemberSavings memberSavings = mock(MemberSavings.class);
    private final RecommendationController controller =
            new RecommendationController(service, narrator, funnel, stats, memberSavings);
    private final java.security.Principal member = () -> "7";
    private final org.springframework.mock.web.MockHttpServletRequest http = new org.springframework.mock.web.MockHttpServletRequest();

    private final CostResult best = new CostResult(42, "넷플플랜", "SKT", 55000, 68500, 13500, 162_000L,
            List.of(new BreakdownLine("기본료", 55000, "OFFICIAL", null)));
    private final RecommendationRequest request = new RecommendationRequest(
            new RecommendationRequest.Required(20, List.of(1L), null), null);
    private final List<MissingInput> missing = List.of(new MissingInput("hasFamilyBundle", "확인 필요", "마이페이지"));
    private final RecommendationResponse.CurrentCost current = new RecommendationResponse.CurrentCost(
            new CostResult(7, "지금", "SKT", 70390, 70390, 0, 0L, List.of()), 15390, 184_680L, 92_340L);

    /** 추천은 계산만 돌려준다 — 내레이터에 닿지 않는다. 설명 자리는 비어 나간다. */
    @Test
    void recommendDoesNotCallTheNarrator() {
        when(service.recommend(any()))
                .thenReturn(new RecommendationResponse(Accuracy.PARTIAL, missing, List.of(best), 127, current, null));

        var response = controller.recommend(request, null, http);

        assertThat(response.data().results()).containsExactly(best);
        assertThat(response.data().candidateCount()).isEqualTo(127);
        assertThat(response.data().current()).isEqualTo(current);
        assertThat(response.data().message()).isNull();
        assertThat(response.data().reasons()).isEmpty();
        assertThat(response.data().notices()).isEmpty();
        verifyNoInteractions(narrator);
    }

    /**
     * D-59 — 절감액 표본은 <b>로그인한 채 결과를 본 회원</b>에게서 나온다. 저장 버튼과 무관하다.
     * 비회원은 기록하지 않고, 지금 요금제를 모르면({@code current == null}) 회원이라도 기록하지 않는다 —
     * 절감액을 만들 수 없는데 0 으로 적으면 "모른다"가 "절감 없음"으로 둔갑한다.
     */
    @Test
    void recordsTheSavingsSampleOnlyForMembersWhoToldUsTheirCurrentPlan() {
        when(service.recommend(any()))
                .thenReturn(new RecommendationResponse(Accuracy.PARTIAL, missing, List.of(best), 127, current, null));
        controller.recommend(request, member, http);
        org.mockito.Mockito.verify(memberSavings).record(7L, 15390L);

        controller.recommend(request, null, http);                     // 비회원
        when(service.recommend(any()))
                .thenReturn(new RecommendationResponse(Accuracy.PARTIAL, missing, List.of(best), 127, null, null));
        controller.recommend(request, member, http);                   // 지금 요금제를 모르는 회원
        org.mockito.Mockito.verifyNoMoreInteractions(memberSavings);
    }

    /** 같은 본문으로 /narrate 를 부르면 1순위 설명이 온다. current 도 그대로 내레이터에 넘어간다. */
    @Test
    void narrateExplainsTheFirstResultWithCurrent() {
        when(service.recommend(any()))
                .thenReturn(new RecommendationResponse(Accuracy.PARTIAL, missing, List.of(best), 127, current, null));
        when(narrator.narrationFor(best, missing, 127, current))
                .thenReturn(new Narrator.Narration("지금보다 월 15,390원 덜 내요.", List.of("넷플릭스가 포함돼요."),
                        List.of("확인 필요 — 마이페이지")));

        var narration = controller.narrate(request).data();

        assertThat(narration.message()).isEqualTo("지금보다 월 15,390원 덜 내요.");
        assertThat(narration.reasons()).containsExactly("넷플릭스가 포함돼요.");
        assertThat(narration.notices()).containsExactly("확인 필요 — 마이페이지");
    }

    /** 내레이터 장애는 빈 설명이다 — 200 이고 결과 경로는 애초에 무관하다. */
    @Test
    void narratorFailureYieldsEmptyNarration() {
        when(service.recommend(any()))
                .thenReturn(new RecommendationResponse(Accuracy.FULL, List.of(), List.of(best), 5, null, null));
        when(narrator.narrationFor(any(), any(), any(), any())).thenReturn(Narrator.Narration.none());

        var narration = controller.narrate(request).data();

        assertThat(narration.message()).isNull();
        assertThat(narration.reasons()).isEmpty();
    }

    /** 후보가 없으면 설명할 것도 없다. 내레이터를 부르지 않는다. */
    @Test
    void noCandidateSkipsNarratorEntirely() {
        when(service.recommend(any())).thenReturn(new RecommendationResponse(Accuracy.PARTIAL, List.of(), List.of()));

        var narration = controller.narrate(request).data();

        assertThat(narration.message()).isNull();
        assertThat(narration.reasons()).isEmpty();
        verifyNoInteractions(narrator);
    }
}
