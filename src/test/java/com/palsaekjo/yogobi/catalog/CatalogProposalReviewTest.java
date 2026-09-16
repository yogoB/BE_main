package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 제안 검토 판정(D-29). 소스는 공식 시세 구현 전부(스마트초이스·통신사 공식)와 AI 조회다.
 * 핵심: **"확인 못 함"과 "틀림"을 구분한다.** 확인 못 함은 막지 않고 사용자 제보에 맡긴다.
 */
class CatalogProposalReviewTest {
    private static final Map<String, String> PLAN = Map.of(
            "carrier", "SKT", "plan_name", "요고 30", "network_type", "5G",
            "base_price", "30000", "data_mb", "30720");

    @Test
    void agreeingSourceConfirmsTheProposal() {
        var result = review(Optional.of(30000L), Optional.empty())
                .review(CatalogAuditLog.Action.CREATE, "mobile_plan", PLAN);
        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.detail()).contains("스마트초이스 30000원 — 일치").contains("AI: 확인 못 함");
    }

    @Test
    void disagreeingSourceBlocksEvenIfTheOtherAgrees() {
        var result = review(Optional.of(30000L), Optional.of(new CatalogVerifier.Finding(39000, 0.9, "https://x")))
                .review(CatalogAuditLog.Action.CREATE, "mobile_plan", PLAN);
        assertThat(result.status()).isEqualTo("MISMATCH");     // 한 곳이라도 다른 금액이면 막는다
        assertThat(result.detail()).contains("AI 39000원").contains("불일치");
    }

    /** 둘 다 확인 못 하면 통과시킨다 — 이후 사용자 제보로 잡는다(D-18). */
    @Test
    void unknownFromBothSourcesDoesNotBlock() {
        var result = review(Optional.empty(), Optional.empty())
                .review(CatalogAuditLog.Action.CREATE, "mobile_plan", PLAN);
        assertThat(result.status()).isEqualTo("UNVERIFIED");
        assertThat(result.detail()).contains("사용자 제보");
    }

    /** 표기 반올림 차이를 불일치로 만들지 않는다. */
    @Test
    void smallDifferenceIsTreatedAsTheSamePrice() {
        assertThat(review(Optional.of(30050L), Optional.empty())
                .review(CatalogAuditLog.Action.CREATE, "mobile_plan", PLAN).status()).isEqualTo("VERIFIED");
        assertThat(review(Optional.of(30200L), Optional.empty())
                .review(CatalogAuditLog.Action.CREATE, "mobile_plan", PLAN).status()).isEqualTo("MISMATCH");
    }

    @Test
    void deletionAndPricelessChangesAreSkipped() {
        var reviewer = review(Optional.of(1L), Optional.empty());
        assertThat(reviewer.review(CatalogAuditLog.Action.DELETE, "mobile_plan", PLAN).status()).isEqualTo("SKIPPED");
        assertThat(reviewer.review(CatalogAuditLog.Action.UPDATE, "mobile_plan",
                Map.of("source_url", "https://example.com")).status()).isEqualTo("SKIPPED");
        assertThat(reviewer.review(CatalogAuditLog.Action.UPDATE, "bundle_product",
                Map.of("price", "9000")).status()).isEqualTo("SKIPPED");   // 대조 대상 데이터셋이 아니다
    }

    /** 구독 티어는 AI 로만 대조한다(스마트초이스는 통신 요금제만 안다). */
    @Test
    void subscriptionTierUsesAiOnly() {
        var result = review(Optional.of(999L), Optional.of(new CatalogVerifier.Finding(13500, 0.8, "https://n")))
                .review(CatalogAuditLog.Action.UPDATE, "subscription_tier",
                        Map.of("name", "넷플릭스 스탠다드", "price", "13500"));
        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.detail()).contains("스마트초이스: 확인 못 함");   // 통신 요금제가 아니므로 묻지 않는다
    }

    /** 소스가 늘면 한 곳만 확인해도 VERIFIED 다. 커버가 겹치지 않는 것이 소스를 늘리는 이유다. */
    @Test
    void anySourceConfirmingIsEnough() {
        var reviewer = new CatalogProposalReview(provider(
                named("스마트초이스", (c, p, d, n) -> Optional.empty()),
                named("통신사 공식", (c, p, d, n) -> Optional.of(30_000L))), provider((CatalogVerifier) null));
        var result = reviewer.review(CatalogAuditLog.Action.CREATE, "mobile_plan", PLAN);
        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.detail()).contains("스마트초이스: 확인 못 함").contains("통신사 공식 30000원 — 일치");
    }

    /** 소스를 늘려도 통과가 쉬워지지 않는다 — 한 곳이라도 다른 금액을 말하면 막는다. */
    @Test
    void oneDisagreeingSourceStillBlocks() {
        var reviewer = new CatalogProposalReview(provider(
                named("스마트초이스", (c, p, d, n) -> Optional.of(30_000L)),
                named("통신사 공식", (c, p, d, n) -> Optional.of(41_800L))), provider((CatalogVerifier) null));
        var result = reviewer.review(CatalogAuditLog.Action.CREATE, "mobile_plan", PLAN);
        assertThat(result.status()).isEqualTo("MISMATCH");
        assertThat(result.detail()).contains("통신사 공식 41800원 — 불일치");
    }

    /** 소스가 예외를 던져도 절차를 깨뜨리지 않는다. */
    @Test
    void failingSourceIsTreatedAsUnknown() {
        PriceOracle broken = named("스마트초이스", (carrier, plan, data, network) -> {
            throw new IllegalStateException("boom");
        });
        var reviewer = new CatalogProposalReview(provider(broken), provider((CatalogVerifier) null));
        assertThat(reviewer.review(CatalogAuditLog.Action.CREATE, "mobile_plan", PLAN).status())
                .isEqualTo("UNVERIFIED");
    }

    private static CatalogProposalReview review(Optional<Long> official, Optional<CatalogVerifier.Finding> ai) {
        PriceOracle oracle = named("스마트초이스", (carrier, plan, data, network) -> official);
        CatalogVerifier verifier = (type, query) -> ai;
        return new CatalogProposalReview(provider(oracle), provider(verifier));
    }

    /** 소스 이름을 붙인다 — 판정 근거에서 어느 곳이 무엇을 말했는지 구분되어야 한다. */
    private static PriceOracle named(String name, PriceOracle delegate) {
        return new PriceOracle() {
            @Override public String sourceName() { return name; }
            @Override public Optional<Long> officialPrice(String carrier, String plan, long data, String network) {
                return delegate.officialPrice(carrier, plan, data, network);
            }
        };
    }

    /** 포트가 없을 수도 있으므로(fail-soft) ObjectProvider 로 받는다 — 테스트에서는 최소 구현만 쓴다. */
    @SafeVarargs
    private static <T> ObjectProvider<T> provider(T... values) {
        List<T> present = Stream.of(values).filter(Objects::nonNull).toList();
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return present.isEmpty() ? null : present.get(0); }
            @Override public T getObject() { return getObject(new Object[0]); }
            @Override public T getIfAvailable() { return getObject(new Object[0]); }
            @Override public T getIfUnique() { return present.size() == 1 ? present.get(0) : null; }
            @Override public Stream<T> stream() { return present.stream(); }
            @Override public Stream<T> orderedStream() { return present.stream(); }
        };
    }
}
