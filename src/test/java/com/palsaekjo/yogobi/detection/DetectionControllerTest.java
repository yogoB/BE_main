package com.palsaekjo.yogobi.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.palsaekjo.yogobi.catalog.CatalogReader;
import com.palsaekjo.yogobi.common.DetectionRule;
import com.palsaekjo.yogobi.detection.domain.DetectionFinding;
import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 탐지 응답의 <b>대상 이름</b>과 <b>내레이터 부재 경로</b>. 이름을 못 찾으면 화면에 `service:12` 같은
 * 내부 참조가 그대로 나가고, 내레이터가 없으면 화면이 통째로 빈다 — 둘 다 사용자가 바로 보는 것이라
 * 검증 없이 두지 않는다(D-46).
 */
class DetectionControllerTest {
    private final DetectionService detection = mock(DetectionService.class);
    private final CatalogReader catalog = mock(CatalogReader.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DetectionNarrator> narrator = mock(ObjectProvider.class);
    private final DetectionController controller = new DetectionController(detection, catalog, narrator);

    private static final Principal MEMBER = () -> "7";

    private static DetectionFinding finding(String targetRef) {
        return DetectionFinding.derived(DetectionRule.TIER_DUPLICATE, targetRef, 13500);
    }

    private void given(DetectionFinding... findings) {
        when(detection.detectForUser(anyLong())).thenReturn(List.of(findings));
        when(catalog.listServices()).thenReturn(List.of(
                new CatalogReader.ServiceView(1, "넷플릭스", "OTT", "https://netflix.com", List.of())));
    }

    /** 내레이터가 없어도 금액과 대상은 우리가 아는 값이다. 문구만 없이 보여준다. */
    @Test
    void withoutANarratorTheAmountsAndTargetsStillShow() {
        given(finding("service:1"), finding("bundle:9"));
        when(narrator.getIfAvailable()).thenReturn(null);

        var view = controller.detections(MEMBER).data();

        assertThat(view.findings()).hasSize(2);
        assertThat(view.lines()).extracting(DetectionNarrator.Explained::target)
                .containsExactly("넷플릭스", "묶음 상품");
        // 문구는 없다. 규칙 이름과 금액 표기만 남는다 — 화면이 통째로 비는 것보다 낫다.
        assertThat(view.lines()).extracting(DetectionNarrator.Explained::amount)
                .containsExactly("월 13,500원", "월 13,500원");
        assertThat(view.lines()).extracting(DetectionNarrator.Explained::how).containsExactly("", "");
        assertThat(view.summary()).isEmpty();
    }

    /** 내레이터가 있으면 이름을 찾아 넘기고 문구를 그대로 싣는다 — 카탈로그는 내레이터가 모른다. */
    @Test
    void withANarratorTheSentencesComeFromIt() {
        given(finding("service:1"));
        DetectionNarrator port = mock(DetectionNarrator.class);
        when(narrator.getIfAvailable()).thenReturn(port);
        when(port.explain(any(), any())).thenReturn(new DetectionNarrator.Explanation(
                List.of(new DetectionNarrator.Explained("같은 등급을 두 번 내고 있어요", "넷플릭스",
                        "월 13,500원", "한쪽을 해지하면 돼요")),
                "월 13,500원이 새고 있어요."));

        var view = controller.detections(MEMBER).data();

        assertThat(view.lines()).singleElement()
                .extracting(DetectionNarrator.Explained::title).isEqualTo("같은 등급을 두 번 내고 있어요");
        assertThat(view.summary()).isEqualTo("월 13,500원이 새고 있어요.");
    }

    /**
     * 카탈로그에 없는 서비스도 <b>막지 않는다</b>(D-17). `service:99` 를 그대로 내보내면 화면에 내부
     * 참조가 찍히므로 번호만 사람이 읽는 꼴로 바꾼다.
     */
    @Test
    void anUnknownServiceBecomesANumberedLabel() {
        given(finding("service:99"));
        when(narrator.getIfAvailable()).thenReturn(null);

        assertThat(controller.detections(MEMBER).data().lines())
                .singleElement().extracting(DetectionNarrator.Explained::target).isEqualTo("서비스 #99");
    }

    /** 모르는 참조는 그대로 둔다 — 지어내지 않는다. 카탈로그를 뒤질 이유도 없다. */
    @Test
    void anUnknownReferenceIsLeftAsItIs() {
        given(finding("promo:3"), finding("이름없음"), finding(null));
        when(narrator.getIfAvailable()).thenReturn(null);

        assertThat(controller.detections(MEMBER).data().lines())
                .extracting(DetectionNarrator.Explained::target)
                .containsExactly("promo:3", "이름없음", "");
        // service: 가 아니면 카탈로그를 부르지 않는다.
        verifyNoInteractions(catalog);
    }

    /** 새는 금액이 없으면 줄도 없다. 빈 목록으로 200 이다 — "없음"도 결과다. */
    @Test
    void noFindingsIsAnEmptyView() {
        given();
        when(narrator.getIfAvailable()).thenReturn(null);

        var view = controller.detections(MEMBER).data();

        assertThat(view.findings()).isEmpty();
        assertThat(view.lines()).isEmpty();
    }
}
