package com.palsaekjo.yogobi.subscription;

import com.palsaekjo.yogobi.subscription.domain.MerchantAlias;
import java.util.List;
import java.util.Optional;

/**
 * 가맹점 원문 → service_id (docs/domain.md §7, G-10). Spring 의존 없는 순수 도메인.
 * 매칭되는 별칭이 없으면 빈 Optional — 모르는 가맹점은 억지로 매핑하지 않고 사용자에게 묻는다.
 */
public final class MerchantNormalizer {

    /** 첫 매칭 별칭의 service_id. 없으면 empty. */
    public Optional<Long> resolve(String merchantRaw, List<MerchantAlias> aliases) {
        if (merchantRaw == null || merchantRaw.isBlank()) {
            return Optional.empty();
        }
        String rawUpper = merchantRaw.toUpperCase();
        return aliases.stream()
                .filter(alias -> alias.matches(rawUpper))
                .map(MerchantAlias::serviceId)
                .findFirst();
    }
}
