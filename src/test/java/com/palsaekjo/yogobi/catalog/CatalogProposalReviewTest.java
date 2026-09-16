package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 제안 검토 판정(D-29). 소스는 두 곳 — 스마트초이스 시세와 AI 조회.
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

    /** 소스가 예외를 던져도 절차를 깨뜨리지 않는다. */
    @Test
    void failingSourceIsTreatedAsUnknown() {
        PriceOracle broken = (carrier, plan, data, network) -> {
            throw new IllegalStateException("boom");
        };
        var reviewer = new CatalogProposalReview(provider(broken), provider(null));
        assertThat(reviewer.review(CatalogAuditLog.Action.CREATE, "mobile_plan", PLAN).status())
                .isEqualTo("UNVERIFIED");
    }

    private static CatalogProposalReview review(Optional<Long> official, Optional<CatalogVerifier.Finding> ai) {
        PriceOracle oracle = (carrier, plan, data, network) -> official;
        CatalogVerifier verifier = (type, query) -> ai;
        return new CatalogProposalReview(provider(oracle), provider(verifier));
    }

    /** 포트가 없을 수도 있으므로(fail-soft) ObjectProvider 로 받는다 — 테스트에서는 최소 구현만 쓴다. */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return value; }
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }
}
