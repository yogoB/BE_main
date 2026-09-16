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
 * <p>① **스마트초이스** 공식 시세(D-23의 1차 교차검증) ② **AI 서버 LLM 조회**(2차 더블체크).
 * 판정 규칙은 BE 가 갖는다 — AI 는 출처를 찾아 보고할 뿐 숫자를 확정하지 않는다(절대 원칙 2·D-03).
 *
 * <ul>
 *   <li>{@code VERIFIED} — 한 곳 이상이 같은 금액을 확인했고, 다른 금액을 말한 곳이 없다.</li>
 *   <li>{@code MISMATCH} — 어느 한 곳이라도 **다른 금액**을 보고했다. 승인을 막는다.</li>
 *   <li>{@code UNVERIFIED} — 둘 다 확인하지 못했다. **막지 않는다** — 이후 사용자 제보로 잡는다(D-18).</li>
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
    private final ObjectProvider<CatalogVerifier> verifier;

    public CatalogProposalReview(ObjectProvider<PriceOracle> oracle, ObjectProvider<CatalogVerifier> verifier) {
        this.oracle = oracle;
        this.verifier = verifier;
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

        Optional<Long> official = smartChoice(dataset, values);
        if (official.isPresent()) {
            boolean same = Math.abs(official.get() - claimed) <= TOLERANCE_WON;
            notes.add("스마트초이스 " + official.get() + "원 — " + (same ? "일치" : "불일치"));
            confirmed |= same;
            mismatched |= !same;
        } else {
            notes.add("스마트초이스: 확인 못 함");
        }

        Optional<CatalogVerifier.Finding> found = ai(dataset, values);
        if (found.isPresent()) {
            var finding = found.get();
            boolean same = Math.abs(finding.monthlyPriceWon() - claimed) <= TOLERANCE_WON;
            notes.add("AI " + finding.monthlyPriceWon() + "원(확신 " + finding.confidence() + ", "
                    + finding.sourceUrl() + ") — " + (same ? "일치" : "불일치"));
            confirmed |= same;
            mismatched |= !same;
        } else {
            notes.add("AI: 확인 못 함");
        }

        String detail = "제안 " + claimed + "원 · " + String.join(" / ", notes);
        if (mismatched) return new Result("MISMATCH", detail);
        if (confirmed) return new Result("VERIFIED", detail);
        return new Result("UNVERIFIED", detail + " — 두 소스 모두 확인하지 못해 그대로 둡니다. 오류는 사용자 제보로 접수됩니다.");
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

    private Optional<Long> smartChoice(String dataset, Map<String, String> values) {
        PriceOracle port = oracle.getIfAvailable();
        if (port == null || !"mobile_plan".equals(dataset)) return Optional.empty();
        String carrier = values.get("carrier");
        String planName = values.get("plan_name");
        String data = values.get("data_mb");
        if (carrier == null || planName == null || data == null) return Optional.empty();
        try {
            return port.officialPrice(carrier.strip(), planName.strip(),
                    Long.parseLong(data.strip()), values.getOrDefault("network_type", ""));
        } catch (RuntimeException e) {
            log.warn("스마트초이스 대조 실패 — 확인 못 함으로 처리 ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<CatalogVerifier.Finding> ai(String dataset, Map<String, String> values) {
        CatalogVerifier port = verifier.getIfAvailable();
        if (port == null) return Optional.empty();
        String productType = "mobile_plan".equals(dataset) ? "MOBILE_PLAN" : "SUBSCRIPTION";
        String query = query(dataset, values);
        if (query == null) return Optional.empty();
        try {
            return port.lookup(productType, query);
        } catch (RuntimeException e) {
            log.warn("AI 대조 실패 — 확인 못 함으로 처리 ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** AI 에 물을 문장. 우리가 아는 식별 정보만 넣는다(금액은 넣지 않는다 — 답을 흘리면 대조가 무의미하다). */
    private static String query(String dataset, Map<String, String> values) {
        List<String> parts = "mobile_plan".equals(dataset)
                ? List.of(text(values, "carrier"), text(values, "plan_name"), text(values, "network_type"))
                : List.of(text(values, "name"));
        String query = String.join(" ", parts.stream().filter(s -> !s.isBlank()).toList()).strip();
        return query.length() < 2 ? null : query + " 월정액";
    }

    private static String text(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null ? "" : value.strip();
    }
}
