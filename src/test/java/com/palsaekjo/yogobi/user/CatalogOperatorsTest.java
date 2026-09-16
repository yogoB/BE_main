package com.palsaekjo.yogobi.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 운영자 허용목록 파싱(D-24). 설정 누락은 전체 개방이 아니라 전체 잠금이어야 한다. */
class CatalogOperatorsTest {
    @Test
    void emptyConfigurationLocksEveryone() {
        assertThat(CatalogOperators.parse("")).isEmpty();
        assertThat(CatalogOperators.parse("   ")).isEmpty();
        assertThat(CatalogOperators.parse(null)).isEmpty();
        assertThat(new CatalogOperators("").contains(1)).isFalse();
    }

    @Test
    void parsesCommaSeparatedIdsIgnoringBlanks() {
        assertThat(CatalogOperators.parse("1, 7 ,,42")).containsExactlyInAnyOrder(1L, 7L, 42L);
        var operators = new CatalogOperators("3");
        assertThat(operators.contains(3)).isTrue();
        assertThat(operators.contains(4)).isFalse();
    }

    /** 오타가 조용히 "운영자 없음"으로 흐르면 설정한 줄 알고 잠기므로, 형식 오류는 기동을 막는다. */
    @Test
    void rejectsNonNumericAndNonPositiveIds() {
        assertThatThrownBy(() -> CatalogOperators.parse("admin"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("형식 오류");
        assertThatThrownBy(() -> CatalogOperators.parse("1,0"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("형식 오류");
        assertThatThrownBy(() -> CatalogOperators.parse("-5"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("형식 오류");
    }
}
