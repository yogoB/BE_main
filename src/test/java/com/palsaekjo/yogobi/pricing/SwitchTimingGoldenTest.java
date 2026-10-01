package com.palsaekjo.yogobi.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.palsaekjo.yogobi.pricing.SwitchTiming.Status;
import org.junit.jupiter.api.Test;

/** docs/testing.md G-11 회수기간. 순수 도메인, Spring 불필요. ceil은 정수 연산이며 내림이면 실패. */
class SwitchTimingGoldenTest {

    @Test void g11e_largeValuesDoNotOverflowBeforeDivision() {
        var r = SwitchTiming.evaluate(Long.MAX_VALUE, Long.MAX_VALUE, 10);
        assertThat(r.paybackMonths()).isEqualTo(1);
        assertThat(r.status()).isEqualTo(Status.SWITCH_NOW);
    }

    @Test void g11f_unrepresentablePaybackIsRejected() {
        assertThatThrownBy(() -> SwitchTiming.evaluate((long) Integer.MAX_VALUE + 1, 1, 10))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test void g11a_switchNow() {
        var r = SwitchTiming.evaluate(60_000, 17_700, 10); // ceil(60000/17700)=4 < 10
        assertThat(r.paybackMonths()).isEqualTo(4);
        assertThat(r.status()).isEqualTo(Status.SWITCH_NOW);
    }

    @Test void g11b_waitUntilExpiry() {
        var r = SwitchTiming.evaluate(60_000, 17_700, 2); // 4 >= 2
        assertThat(r.paybackMonths()).isEqualTo(4);
        assertThat(r.status()).isEqualTo(Status.WAIT_UNTIL_EXPIRY);
    }

    @Test void g11c_noBenefitOnZeroSavings() {
        var r = SwitchTiming.evaluate(60_000, 0, 10); // 0 나눗셈 방어
        assertThat(r.status()).isEqualTo(Status.NO_BENEFIT);
        assertThat(r.paybackMonths()).isNull();
    }

    @Test void g11g_expiredContractSwitchesNowRegardlessOfPayback() {
        var r = SwitchTiming.evaluate(60_000, 17_700, 0); // 약정 끝 → 기다릴 대상 없음 (회수 4 > 잔여 0 이어도)
        assertThat(r.paybackMonths()).isEqualTo(4);
        assertThat(r.status()).isEqualTo(Status.SWITCH_NOW);
    }

    @Test void g11d_zeroSwitchingCostSwitchesNow() {
        var r = SwitchTiming.evaluate(0, 17_700, 10); // ceil(0)=0 < 10
        assertThat(r.paybackMonths()).isZero();
        assertThat(r.status()).isEqualTo(Status.SWITCH_NOW);
    }

    /** 특가 안에 회수되면 예전과 같다. */
    @Test void g11h_paybackWithinPromoIsUnchanged() {
        var r = SwitchTiming.evaluate(60_000, 17_700, 10, 6, -5_000L); // 4 <= 6
        assertThat(r.paybackMonths()).isEqualTo(4);
        assertThat(r.status()).isEqualTo(Status.SWITCH_NOW);
    }

    /** 특가가 끝나면 남은 비용을 줄어든 절감으로 센다: 6×5000=30000, 남은 20000 ÷ 2000 = 10 → 16. */
    @Test void g11i_paybackContinuesWithSmallerSavingsAfterPromo() {
        var r = SwitchTiming.evaluate(50_000, 5_000, 12, 6, 2_000L);
        assertThat(r.paybackMonths()).isEqualTo(16);   // 월만 보면 10 → SWITCH_NOW 였다
        assertThat(r.status()).isEqualTo(Status.WAIT_UNTIL_EXPIRY);
    }

    /** 특가 안에 못 갚고 그 뒤 절감이 없으면 영영 회수하지 못한다. */
    @Test void g11j_neverRecoupedAfterPromoIsNoBenefit() {
        var r = SwitchTiming.evaluate(50_000, 5_000, 0, 6, -1_000L);
        assertThat(r.paybackMonths()).isNull();
        assertThat(r.status()).isEqualTo(Status.NO_BENEFIT);
        assertThat(r.monthlySavings()).isEqualTo(5_000);   // 특가 동안의 월 절감은 그대로 싣는다
    }

    /** 특가 뒤 금액을 모르면(정상가 미상) 예전처럼 월로만 센다 — 모르는 값을 지어내지 않는다. */
    @Test void g11k_unknownAfterPromoFallsBackToMonthly() {
        var r = SwitchTiming.evaluate(50_000, 5_000, 12, 6, null);
        assertThat(r.paybackMonths()).isEqualTo(10);
        assertThat(r.status()).isEqualTo(Status.SWITCH_NOW);
    }

    /** G-11 l. 회수 4 = 잔여 4 — 만료까지 70,800원이 쌓여 비용 60,000원보다 10,800원 이득이다. 전엔 "기다리세요"였다. */
    @Test void g11l_paybackEqualToRemainingStillGainsSwitchesNow() {
        var r = SwitchTiming.evaluate(60_000, 17_700, 4);
        assertThat(r.paybackMonths()).isEqualTo(4);
        assertThat(r.status()).isEqualTo(Status.SWITCH_NOW);
    }

    /** G-11 m. 만료까지 쌓인 절감이 비용과 같으면 옮겨도 이득이 없다 — 기다린다. */
    @Test void g11m_breakEvenAtExpiryWaits() {
        var r = SwitchTiming.evaluate(70_800, 17_700, 4);
        assertThat(r.paybackMonths()).isEqualTo(4);
        assertThat(r.status()).isEqualTo(Status.WAIT_UNTIL_EXPIRY);
    }

    /** G-11 n. 특가 두 구간에서도 같은 경계: 30,000 + 10×2,000 = 50,000 > 49,000. */
    @Test void g11n_promoBoundaryUsesAccumulatedSavings() {
        var r = SwitchTiming.evaluate(49_000, 5_000, 16, 6, 2_000L);
        assertThat(r.paybackMonths()).isEqualTo(16);
        assertThat(r.status()).isEqualTo(Status.SWITCH_NOW);
    }

    /** G-11 o. 경계가 특가 안이면 특가 절감만 센다: 4×17,700 = 70,800 > 60,000. */
    @Test void g11o_boundaryInsidePromoUsesPromoSavings() {
        var r = SwitchTiming.evaluate(60_000, 17_700, 4, 6, 1_000L);
        assertThat(r.status()).isEqualTo(Status.SWITCH_NOW);
    }

    /** G-11 p. 특가 뒤를 모르면 월로만 센다: 10×5,000 = 50,000 = 비용 → 이득 없음. */
    @Test void g11p_boundaryWithUnknownAfterPromoUsesMonthly() {
        var r = SwitchTiming.evaluate(50_000, 5_000, 10, 6, null);
        assertThat(r.paybackMonths()).isEqualTo(10);
        assertThat(r.status()).isEqualTo(Status.WAIT_UNTIL_EXPIRY);
    }
}
