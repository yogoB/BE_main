package com.palsaekjo.yogobi.subscription.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.palsaekjo.yogobi.common.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MockMydataProviderTest {
    private final MockMydataProvider provider = new MockMydataProvider(new ObjectMapper());

    @Test void acceptsWonApprovalsAndSkipsCancellations() {
        var result = provider.parse("{\"approved_list\":[" + payment("13500", "KRW", "20260914123000")
                + ",{\"status\":\"02\"}]}");
        assertThat(result).containsExactly(new PaymentHistoryProvider.ImportedPayment(
                "NETFLIX", 13500, LocalDate.of(2026, 9, 14)));
    }

    @Test void rejectsMalformedJsonAndInvalidMoneyWithoutCoercion() {
        for (String payload : List.of("{", "null", "{}",
                wrap(payment("1.5", "KRW", "20260914123000")),
                wrap(payment("\"13500\"", "KRW", "20260914123000")),
                wrap(payment("9223372036854775808", "KRW", "20260914123000")),
                wrap(payment("-1", "KRW", "20260914123000")),
                wrap(payment("100", "USD", "20260914123000")),
                wrap(payment("100", "KRW", "20260230123000")),
                wrap(payment("100", "KRW", "20260914253000")))) {
            assertThatThrownBy(() -> provider.parse(payload)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.status()).isEqualTo(400));
        }
    }

    private static String wrap(String item) { return "{\"approved_list\":[" + item + "]}"; }

    private static String payment(String amount, String currency, String date) {
        return "{\"status\":\"01\",\"merchant_name\":\"NETFLIX\",\"approved_amt\":" + amount
                + ",\"currency_code\":\"" + currency + "\",\"approved_dtime\":\"" + date + "\"}";
    }
}
