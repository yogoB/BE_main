package com.palsaekjo.yogobi.user;

import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 카탈로그 원본을 고칠 수 있는 운영자 허용목록(D-24). `CATALOG_ADMIN_USER_IDS=1,7` 형태.
 *
 * <p>회원 전체가 아니라 **명시적으로 지정된 회원만** 카탈로그를 쓴다. 비어 있으면 아무도 못 쓴다(기본 잠금) —
 * 설정 누락이 전체 개방으로 이어지면 안 된다. 역할 컬럼·관리 화면을 새로 만들지 않고 배포 설정으로 지정한다
 * (D-07 범위 유지). ponytail: 운영자가 자주 바뀌면 그때 `app_user`에 역할 컬럼을 둔다.
 */
@Component
public class CatalogOperators {
    private static final Logger log = LoggerFactory.getLogger(CatalogOperators.class);
    private final Set<Long> userIds;
    private final AdminAccount admin;

    public CatalogOperators(@Value("${CATALOG_ADMIN_USER_IDS:}") String configured, AdminAccount admin) {
        this.userIds = parse(configured);
        this.admin = admin;
        if (userIds.isEmpty()) log.info("카탈로그 운영자 목록 비어 있음 — 백오피스 관리자 계정만 운영자다(D-32)");
        else log.info("카탈로그 운영자 {}명 지정됨", userIds.size());
    }

    /** 백오피스 관리자 계정(D-32)은 목록에 없어도 운영자다 — 그 계정의 존재 이유가 운영이다. */
    public boolean contains(long userId) {
        return userIds.contains(userId) || (admin.id() != 0 && admin.id() == userId);
    }

    /** 쉼표 구분 양의 정수만 받는다. 공백·빈 항목은 무시하고, 숫자가 아니면 설정 오류로 기동을 막는다. */
    static Set<Long> parse(String configured) {
        var ids = new LinkedHashSet<Long>();
        if (configured == null) return Set.of();
        for (String part : configured.split(",")) {
            String value = part.strip();
            if (value.isEmpty()) continue;
            long id;
            try {
                id = Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("CATALOG_ADMIN_USER_IDS 형식 오류: " + value);
            }
            if (id < 1) throw new IllegalStateException("CATALOG_ADMIN_USER_IDS 형식 오류: " + value);
            ids.add(id);
        }
        return Set.copyOf(ids);
    }
}
