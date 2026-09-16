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
}
