package com.palsaekjo.yogobi.catalog;

import java.util.Optional;

/**
 * AI 서버로 상품 정보를 조회하는 포트(D-29). 구현은 chat 모듈의 AI 게이트웨이다.
 * **AI 는 숫자를 만들지 않는다**(절대 원칙 2) — 여기서도 AI 는 공개된 출처를 찾아 보고할 뿐이고,
 * 우리 값과 맞는지 판정하는 규칙은 BE 가 갖는다({@link CatalogProposalReview}).
 */
public interface CatalogVerifier {
    /** AI 가 찾은 상품 1건. 확인 못 하면 empty. */
    record Finding(long monthlyPriceWon, double confidence, String sourceUrl) {
    }

    Optional<Finding> lookup(String productType, String query);
}
