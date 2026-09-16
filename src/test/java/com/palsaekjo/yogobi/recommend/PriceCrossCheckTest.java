package com.palsaekjo.yogobi.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.palsaekjo.yogobi.catalog.CatalogReader.CandidatePlan;
import com.palsaekjo.yogobi.pricing.domain.MobilePlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 대조 규칙은 BE 가 갖는다(절대 원칙 2). 스냅샷에 없으면 "틀렸다"가 아니라 "확인 못 했다"다.
 * 검증값이 금액·순위에 새어 들어가지 않는지도 여기서 못박는다(D-03·D-20).
 */
class PriceCrossCheckTest {

    private static CandidatePlan plan(long basePrice) {
        return new CandidatePlan(new MobilePlan(1L, "5G 시그니처", basePrice, 0, List.of()), "SKT");
    }

    /** 조회 결과를 대신 돌려주는 리더. DB 없이 규칙만 본다. */
    private static PriceCrossCheck checkWith(boolean collected, boolean carrierCovered, Long officialPrice) {
        var reader = new SmartChoiceSnapshotReader((JdbcTemplate) null) {
            @Override
            public Lookup lookup(String carrier, String planName) {
                return new Lookup(collected, carrierCovered, officialPrice == null ? Optional.empty()
                        : Optional.of(new Snapshot(officialPrice, "스마트초이스(KTOA)", "https://x", Instant.EPOCH)));
            }
        };
        return new PriceCrossCheck(reader);
    }

    /** 모았고, 통신사도 있고, 요금제도 찾은 경우. */
    private static PriceCrossCheck checkWith(Long officialPrice) {
        return checkWith(true, officialPrice != null, officialPrice);
    }

    @Test
    void 기본료가_공식_시세와_같으면_MATCH() {
        var verdict = checkWith(89_000L).check(plan(89_000L));
        assertThat(verdict.status()).isEqualTo(PriceCrossCheck.Status.MATCH);
        assertThat(verdict.officialPrice()).isEqualTo(89_000L);
        assertThat(verdict.sourceUrl()).isEqualTo("https://x");
    }

    @Test
    void 다르면_MISMATCH_이고_양쪽_금액을_남긴다() {
        var verdict = checkWith(85_000L).check(plan(89_000L));
        assertThat(verdict.status()).isEqualTo(PriceCrossCheck.Status.MISMATCH);
        assertThat(verdict.officialPrice()).isEqualTo(85_000L);   // 카탈로그 값은 바뀌지 않는다
    }

    @Test
    void 아직_모으지_않았으면_UNVERIFIED() {
        var verdict = checkWith(false, false, null).check(plan(89_000L));
        assertThat(verdict.status()).isEqualTo(PriceCrossCheck.Status.UNVERIFIED);
    }

    @Test
    void 스마트초이스가_주지_않는_통신사면_대조_대상이_아니다() {
        // 실측 응답의 통신사는 SKT·KT·LGU+ 뿐 — 알뜰폰은 영영 안 온다. "확인 못 했다"와 구분한다.
        var verdict = checkWith(true, false, null).check(plan(89_000L));
        assertThat(verdict.status()).isEqualTo(PriceCrossCheck.Status.NOT_APPLICABLE);
        assertThat(verdict.officialPrice()).isNull();
    }

    @Test
    void 모았고_통신사도_있는데_요금제만_없으면_UNVERIFIED_이고_금액을_지어내지_않는다() {
        var verdict = checkWith(true, true, null).check(plan(89_000L));
        assertThat(verdict.status()).isEqualTo(PriceCrossCheck.Status.UNVERIFIED);
        assertThat(verdict.officialPrice()).isNull();             // 0원으로 적지 않는다
        assertThat(verdict.source()).isNull();
    }

    @Test
    void 대조_결과는_금액에_영향을_주지_않는다() {
        var result = new CostResult(1L, "5G 시그니처", "SKT", 73_900L, 89_000L, 15_100L, 181_200L, List.of());
        var checked = result.withPriceCrossCheck(checkWith(85_000L).check(plan(89_000L)));
        assertThat(checked.monthlyTotal()).isEqualTo(result.monthlyTotal());
        assertThat(checked.baseline()).isEqualTo(result.baseline());
        assertThat(checked.monthlySavings()).isEqualTo(result.monthlySavings());
        assertThat(checked.annualSavings()).isEqualTo(result.annualSavings());
    }
}
