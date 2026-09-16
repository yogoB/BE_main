package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.catalog.PriceOracle;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 스마트초이스 공식 시세로 요금제 금액을 대조한다(D-29, D-23의 1차 교차검증).
 *
 * <p>스마트초이스 Open API 는 "이 요금제를 알려줘"가 아니라 **사용조건으로 추천 몇 건**을 주는 API 다.
 * 그래서 제안된 요금제의 조건(데이터·망)으로 조회한 뒤 (통신사, 요금제명)이 같은 항목을 찾는다.
 * 추천 목록에 없으면 empty — **"틀렸다"가 아니라 "확인 못 했다"** 이고, 그때는 사용자 제보로 잡는다.
 */
@Component
public class SmartChoicePriceOracle implements PriceOracle {
    private static final int AGE = 20;          // 성인 기준
    private static final int CONTRACT_MONTHS = 24;

    private final SmartChoiceClient client;

    public SmartChoicePriceOracle(SmartChoiceClient client) {
        this.client = client;
    }

    @Override
    public Optional<Long> officialPrice(String carrier, String planName, long dataMb, String networkType) {
        if (!client.enabled()) return Optional.empty();
        int data = (int) Math.min(Math.max(dataMb, 0), SmartChoiceClient.UNLIMITED);
        for (SmartChoiceRecommendation found : client.recommend(data, SmartChoiceClient.UNLIMITED,
                SmartChoiceClient.UNLIMITED, AGE, networkCode(networkType), CONTRACT_MONTHS)) {
            if (carrier.equalsIgnoreCase(found.carrier().strip()) && planName.equals(found.planName().strip()))
                return Optional.of(found.planPrice());
        }
        return Optional.empty();
    }

    /** data.md §2: 3G=2 · LTE=3 · 5G=6. 모르는 표기는 LTE 로 둔다(조회 실패는 empty 로 흡수된다). */
    private static int networkCode(String networkType) {
        return switch (networkType == null ? "" : networkType.strip().toUpperCase()) {
            case "5G", "FIVE_G" -> 6;
            case "3G", "THREE_G" -> 2;
            default -> 3;
        };
    }
}
