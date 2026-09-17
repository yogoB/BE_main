package com.palsaekjo.yogobi.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 변경 제안 내용의 자동 검토(D-29). 사람이 눈으로 확인하는 대신 두 소스로 대조한다.
 *
 * <p>소스는 {@link PriceOracle} 구현 전부다 — 스마트초이스 조건별 추천과 통신사 공식 목록.
 * D-45 로 모델 조회를 뺐다. 둘 다 1차 출처를 직접 읽으므로 판정 근거에 URL 이 남는다.
 *
 * <ul>
 *   <li>{@code VERIFIED} — 한 곳 이상이 같은 금액을 확인했고, 다른 금액을 말한 곳이 없다.</li>
 *   <li>{@code MISMATCH} — 어느 한 곳이라도 **다른 금액**을 보고했다. 승인을 막는다.</li>
 *   <li>{@code UNVERIFIED} — 어느 소스도 확인하지 못했다. **막지 않는다** — 이후 사용자 제보로 잡는다(D-18).</li>
 *   <li>{@code SKIPPED} — 대조할 금액이 없는 변경(삭제 등).</li>
 * </ul>
 *
 * <p>외부 호출은 **운영자의 승인 절차에서만** 일어난다. 사용자 요청 경로는 여전히 외부를 부르지 않는다(D-05·D-17).
 */
@Component
public class CatalogProposalReview {
    private static final Logger log = LoggerFactory.getLogger(CatalogProposalReview.class);
    /** 금액이 이만큼 이내로 다르면 같은 값으로 본다. 표기 반올림 차이를 오탐으로 만들지 않는다. */
    private static final long TOLERANCE_WON = 100;

    /** 검토 결과. {@code detail} 은 운영자가 읽을 근거 요약이다. */
    public record Result(String status, String detail) {
    }

    private final ObjectProvider<PriceOracle> oracle;

    public CatalogProposalReview(ObjectProvider<PriceOracle> oracle) {
        this.oracle = oracle;
    }

    /**
     * 제안을 검토한다. 값이 없거나 대조 대상이 아니면 SKIPPED.
     * 어떤 소스도 예외로 절차를 깨뜨리지 않는다 — 조회 실패는 "확인 못 함"으로 접는다.
     */
    public Result review(CatalogAuditLog.Action action, String dataset, Map<String, String> values) {
        if (action == CatalogAuditLog.Action.DELETE || values == null || values.isEmpty())
            return new Result("SKIPPED", "삭제·빈 변경은 대조할 금액이 없습니다.");

        Long claimed = price(dataset, values);
        if (claimed == null) return new Result("SKIPPED", "이 변경에는 대조할 월정액이 없습니다.");

        var notes = new ArrayList<String>();
        boolean confirmed = false;
        boolean mismatched = false;

        for (PriceOracle port : oracle.orderedStream().toList()) {
            Optional<Long> official = officialPrice(port, dataset, values);
            if (official.isPresent()) {
                boolean same = Math.abs(official.get() - claimed) <= TOLERANCE_WON;
                notes.add(port.sourceName() + " " + official.get() + "원 — " + (same ? "일치" : "불일치"));
                confirmed |= same;
                mismatched |= !same;
            } else {
                notes.add(port.sourceName() + ": 확인 못 함");
            }
        }

        String detail = "제안 " + claimed + "원 · " + String.join(" / ", notes);
        if (mismatched) return new Result("MISMATCH", detail);
        if (confirmed) return new Result("VERIFIED", detail);
        return new Result("UNVERIFIED", detail + " — 어느 소스도 확인하지 못해 그대로 둡니다. 오류는 사용자 제보로 접수됩니다.");
    }

    /** 대조 기준 금액. 요금제는 기본료, 구독 티어는 가격. 그 밖의 데이터셋은 대조하지 않는다. */
    private static Long price(String dataset, Map<String, String> values) {
        String raw = switch (dataset) {
            case "mobile_plan" -> values.get("base_price");
            case "subscription_tier" -> values.get("price");
            default -> null;
        };
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Optional<Long> officialPrice(PriceOracle port, String dataset, Map<String, String> values) {
        if (!"mobile_plan".equals(dataset)) return Optional.empty();
        String carrier = values.get("carrier");
        String planName = values.get("plan_name");
        String data = values.get("data_mb");
        if (carrier == null || planName == null || data == null) return Optional.empty();
        try {
            return port.officialPrice(carrier.strip(), planName.strip(),
                    Long.parseLong(data.strip()), values.getOrDefault("network_type", ""));
        } catch (RuntimeException e) {
            log.warn("{} 대조 실패 — 확인 못 함으로 처리 ({})", port.sourceName(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
