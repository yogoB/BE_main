package com.palsaekjo.yogobi.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 통신사 공식 목록 대조의 값 해석 규칙. 네트워크를 타지 않는다. */
class CarrierSitePriceOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Long won(String field) throws Exception {
        return CarrierSitePriceOracle.won(JSON.readTree("{\"v\":" + field + "}").path("v"));
    }

    @Test
    void 쉼표_표기를_숫자로_읽는다() throws Exception {
        assertThat(won("\"33,000\"")).isEqualTo(33_000L);
        assertThat(won("\"9920\"")).isEqualTo(9_920L);
        assertThat(won("0.0")).isEqualTo(0L);
    }

    @Test
    void 값이_없으면_null_이다() throws Exception {
        assertThat(won("null")).isNull();
        assertThat(won("\"\"")).isNull();
        assertThat(won("\"미정\"")).isNull();
        assertThat(CarrierSitePriceOracle.won(null)).isNull();
    }

    /** 어댑터가 없는 사업자는 조회하지 않는다 — 확인 못 한 것과 구분되도록 조용히 empty 다. */
    @Test
    void 어댑터가_없는_사업자는_묻지_않는다() {
        var oracle = new CarrierSitePriceOracle(RestClient.builder());
        assertThat(oracle.officialPrice("KT스카이라이프", "요금제", 11_264, "LTE")).isEqualTo(Optional.empty());
        assertThat(oracle.officialPrice(null, "x", 0, "LTE")).isEqualTo(Optional.empty());
        assertThat(oracle.officialPrice("SK세븐모바일", null, 0, "LTE")).isEqualTo(Optional.empty());
    }

    @Test
    void 지원_사업자_목록이_노출된다() {
        assertThat(new CarrierSitePriceOracle(RestClient.builder()).supportedCarriers())
                .containsExactly("KT엠모바일", "LG헬로모바일", "SK세븐모바일");
    }

    @Test
    void 소스_이름이_판정_근거에_구분되어_적힌다() {
        assertThat(new CarrierSitePriceOracle(RestClient.builder()).sourceName()).isEqualTo("통신사 공식");
    }
}
