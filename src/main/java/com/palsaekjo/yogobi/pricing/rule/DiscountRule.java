package com.palsaekjo.yogobi.pricing.rule;

import com.palsaekjo.yogobi.common.Provenance;
import com.palsaekjo.yogobi.pricing.domain.Money;
import com.palsaekjo.yogobi.pricing.domain.PricingContext;

/** docs/domain.md §4: priority()로 적용 순서를 고정한다. */
public interface DiscountRule {
    boolean applies(PricingContext ctx);

    Money apply(Money base, PricingContext ctx);

    int priority();

    /**
     * 계산 내역에 찍히는 할인 이름. <b>한글로 끝나게 짓는다.</b>
     *
     * <p>내레이터가 이 라벨을 문장에 끼워 넣으며 <b>끝 글자를 보고 조사를 고른다</b>
     * (`“약정할인”으로 월 13,750원이 빠져요.`). 한글이 아닌 글자로 끝나면 조사가 `"으로"` 로
     * 고정되므로, `"Google One 2TB"` 같은 이름을 쓰면 `"2TB으로"` 가 된다 — 자연스러운 건 `"2TB로"` 다.
     * 지금 셋은 전부 "할인" 으로 끝나 문제가 없고, 그래서 내레이터 쪽에 영문·숫자 읽기 규칙을
     * <b>넣지 않기로 했다</b>(AI 세션과 합의, 2026-09-21). 없는 경우를 위한 규칙은 과잉이고,
     * 폴백이 문법적으로 틀리지도 않는다.
     *
     * <p>그래서 <b>제약이 여기에 있다.</b> 새 할인 라벨이 한글로 안 끝나면 그때 내레이터에 알린다.
     * 판정을 그쪽에 두면 그게 곧 라벨 문자열 매칭이고, 값을 만드는 쪽이 지킬 약속이다.
     */
    String label();

    /**
     * 이 규칙이 만든 금액의 출처(절대 원칙 4). 기본은 우리가 계산한 값이라 {@code DERIVED} 다.
     * 사용자가 말해 준 금액을 그대로 쓰는 규칙만 이 값을 바꾼다 — 계산한 적 없는 값을
     * 계산값이라 적으면 근거를 펼쳤을 때 거짓말이 된다.
     */
    default Provenance provenance() {
        return Provenance.DERIVED;
    }
}
