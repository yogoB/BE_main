package com.palsaekjo.yogobi.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 내레이터가 닿지 않을 때의 배지가 내레이터와 같은 말을 한다. */
class SwitchTimingNarratorTest {

    private static SwitchTimingService.Response timing(long monthlySavings, String status) {
        return new SwitchTimingService.Response(30_000, 30_000 - monthlySavings, monthlySavings, 50_000, null, 0, status);
    }

    @Test
    void neverRecoupedAfterPromotionIsNotCalledNoSaving() {
        assertThat(SwitchTimingNarrator.fallback(timing(5_000, "NO_BENEFIT")).headline())
                .isEqualTo("전환비용 회수 불가 · 참고용 일정");
        assertThat(SwitchTimingNarrator.fallback(timing(0, "NO_BENEFIT")).headline())
                .isEqualTo("절감 없음 · 참고용 일정");
        assertThat(SwitchTimingNarrator.fallback(timing(5_000, "SWITCH_NOW")).headline())
                .isEqualTo("지금이 최적 실행 시점");
    }
}
