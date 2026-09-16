package com.palsaekjo.yogobi.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 운영자 허용목록 파싱(D-24)과 백오피스 관리자 인정(D-32). 설정 누락은 전체 개방이 아니라 전체 잠금이어야 한다. */
class CatalogOperatorsTest {
    @Test
    void emptyConfigurationLocksEveryone() {
        assertThat(CatalogOperators.parse("")).isEmpty();
        assertThat(CatalogOperators.parse("   ")).isEmpty();
        assertThat(CatalogOperators.parse(null)).isEmpty();
        assertThat(operators("", 0).contains(1)).isFalse();
    }

    @Test
    void parsesCommaSeparatedIdsIgnoringBlanks() {
        assertThat(CatalogOperators.parse("1, 7 ,,42")).containsExactlyInAnyOrder(1L, 7L, 42L);
        var operators = operators("3", 0);
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

    /** 백오피스 관리자 계정은 허용목록이 비어 있어도 운영자다(D-32). */
    @Test
    void backofficeAdminIsAlwaysAnOperator() {
        assertThat(operators("", 9).contains(9)).isTrue();
        assertThat(operators("", 9).contains(8)).isFalse();
        assertThat(operators("8", 9).contains(8)).isTrue();
    }

    /** 관리자 미설정(id=0)일 때 0 을 운영자로 오인하면 안 된다. */
    @Test
    void unconfiguredAdminGrantsNothing() {
        assertThat(operators("", 0).contains(0)).isFalse();
    }

    private static CatalogOperators operators(String configured, long adminId) {
        var admin = new AdminAccount(null, null, null, "", "") {
            @Override
            public long id() {
                return adminId;
            }
        };
        return new CatalogOperators(configured, admin);
    }
}
