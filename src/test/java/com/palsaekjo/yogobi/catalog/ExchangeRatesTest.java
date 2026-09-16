package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.palsaekjo.yogobi.catalog.ExchangeRates.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** G-17 i·h: 환산은 원 단위 내림이고, 환율이 없으면 환산 자체를 만들지 않는다(Spring·네트워크 불필요). */
class ExchangeRatesTest {
    private static final Rate USD_KRW =
            new Rate("USD", "KRW", new BigDecimal("1359.15"), LocalDate.parse("2026-09-15"), "https://example.test");

    @Test void convertsWithFloorSoTheEstimateNeverLooksBigger() {
        assertThat(ExchangeRates.toKrw(20, USD_KRW, true)).isEqualTo(27183);   // 27183.0
        assertThat(ExchangeRates.toKrw(8, USD_KRW, true)).isEqualTo(10873);    // 10873.2 → 내림
        assertThat(ExchangeRates.toKrw(200, USD_KRW, true)).isEqualTo(271830);
    }

    @Test void zeroAndSmallAmountsStayExact() {
        assertThat(ExchangeRates.toKrw(0, USD_KRW, true)).isZero();
        assertThat(ExchangeRates.toKrw(1, USD_KRW, true)).isEqualTo(1359);
    }

    /** G-17 j: 표기가가 세금 별도면 실제 결제액은 10% 더 크다. 표기가는 그대로 두고 환산에서만 더한다. */
    @Test void taxExcludedPriceAddsKoreanVatBeforeConverting() {
        assertThat(ExchangeRates.toKrw(20, USD_KRW, false)).isEqualTo(29901);   // 20 × 1.1 × 1359.15 = 29901.3
        assertThat(ExchangeRates.toKrw(8, USD_KRW, false)).isEqualTo(11960);    // 8 × 1.1 × 1359.15 = 11960.5
        assertThat(ExchangeRates.toKrw(0, USD_KRW, false)).isZero();
    }
}
