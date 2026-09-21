package com.palsaekjo.yogobi.admin;

import com.palsaekjo.yogobi.catalog.CatalogAuditLog;
import com.palsaekjo.yogobi.catalog.CatalogChangeRequests;
import com.palsaekjo.yogobi.catalog.SubscriptionPriceOracle;
import com.palsaekjo.yogobi.user.AdminAccount;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 매일 09:00(KST) 카탈로그 수집(D-32). 수집 결과를 **바로 반영하지 않고 변경 제안으로 만든다** —
 * 그래야 자동검토(D-29)와 운영자 검수·승인(D-28)을 그대로 탄다. 이 클래스는 "제안을 만드는 일"만 한다.
 *
 * <p>두 소스에서 우리 값과 다른 것만 골라낸다.
 * <ul>
 *   <li><b>요금제</b> — 스마트초이스 스냅샷(`smartchoice_plan_snapshot`, 배치 수집분)과 기본료 비교.</li>
 *   <li><b>구독</b> — 내레이터 레포의 공식가 조회({@link SubscriptionPriceOracle})와 등급 가격 비교.
 *       D-45 로 비어 있던 자리를 D-60 이 채운다. 저쪽은 공식 페이지를 읽어 원문을 인용할 뿐이고,
 *       대조·제안은 여기서 한다.</li>
 * </ul>
 *
 * <p>사용자 요청 경로는 여전히 외부를 부르지 않는다(D-05·D-17). 외부 호출은 이 배치와 승인 절차에서만 일어난다.
 * 한 번 실행에 만드는 제안 수를 제한한다 — 소스가 흔들린 날 검수함이 수백 건으로 덮이면 아무도 검수하지 않는다.
 */
@Service
public class CatalogDailyHarvest {
    private static final Logger log = LoggerFactory.getLogger(CatalogDailyHarvest.class);
    /** 요금제 금액이 이만큼 이내로 다르면 바꿀 값이 아니다(검토 허용 오차와 같은 기준). */
    private static final long TOLERANCE_WON = 100;
    /**
     * <b>구독은 허용 오차가 0 이다.</b> 요금제의 100원은 스마트초이스와 우리 표기가 다를 수 있어 둔 값이지만,
     * 구독은 그 행의 {@code official_url} 이 가리키는 바로 그 페이지에서 찍힌 정수를 그대로 읽어 온다 —
     * 반올림이 끼어들 자리가 없다. 그리고 100원을 허용하면 iCloud+ 50GB(1,100원)가 1,190원으로 올라도
     * 조용히 지나간다. 요금제 5만원에서 100원은 0.2%지만 1,100원에서는 9%다.
     */
    private static final long SUBSCRIPTION_TOLERANCE_WON = 0;
    private static final Pattern DATA_AMOUNT = Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(GB|MB)",
            Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final CatalogChangeRequests requests;
    private final AdminAccount admin;
    private final SubscriptionPriceOracle subscriptions;
    private final com.palsaekjo.yogobi.catalog.PlanPromotionOracle promotions;
    private final AdminActions actions;
    private final int planLimit;
    private final int subscriptionLimit;
    /** 공식가를 확인할 서비스. 이름은 {@code subscription_service.name} 과 글자까지 같아야 붙는다. */
    private final List<String> subscriptionServices;

    public CatalogDailyHarvest(JdbcTemplate jdbc, CatalogChangeRequests requests, AdminAccount admin,
                               SubscriptionPriceOracle subscriptions,
                               com.palsaekjo.yogobi.catalog.PlanPromotionOracle promotions, AdminActions actions,
                               @Value("${yogobi.harvest.plan-limit:20}") int planLimit,
                               @Value("${yogobi.harvest.subscription-limit:20}") int subscriptionLimit,
                               @Value("${yogobi.harvest.subscription-services:Spotify,Apple Music,iCloud+}")
                               List<String> subscriptionServices) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.admin = admin;
        this.subscriptions = subscriptions;
        this.promotions = promotions;
        this.actions = actions;
        this.planLimit = planLimit;
        this.subscriptionLimit = subscriptionLimit;
        this.subscriptionServices = subscriptionServices;
    }

    /** 한국시간 매일 09:00. 실패해도 다음 날 다시 돈다 — 카탈로그는 그대로 유지된다(fail-soft). */
    @Scheduled(cron = "${yogobi.harvest.cron:0 0 9 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        try {
            Map<String, Object> result = harvest();
            log.info("일일 카탈로그 수집 완료: {}", result);
        } catch (RuntimeException e) {
            log.warn("일일 카탈로그 수집 실패 — 카탈로그는 그대로 유지, 다음 실행에 재시도 ({})",
                    e.getClass().getSimpleName());
        }
    }

    /** 수집 1회. 만들어진 제안 수를 돌려준다. 이미 같은 대상의 PENDING 제안이 있으면 건너뛴다. */
    public Map<String, Object> harvest() {
        long proposer = admin.id();
        int plans = harvestPlans(proposer);
        var out = new LinkedHashMap<String, Object>();
        out.put("proposedMobilePlans", plans);
        out.put("proposedSubscriptionTiers", harvestSubscriptions(proposer));
        out.put("proposedPlanPromotions", harvestPromotions(proposer));
        out.put("pending", jdbc.queryForObject(
                "SELECT count(*) FROM catalog_change_request WHERE status = 'PENDING'", Long.class));
        return out;
    }

    /**
     * 스마트초이스가 우리와 다른 기본료를 말하는 요금제를 제안으로 만든다.
     * 스냅샷은 무약정(contract_months 최소) 정상가를 기준으로 본다 — 우리 `base_price` 와 같은 성격의 값이다.
     */
    private int harvestPlans(long proposer) {
        int made = harvestMissingYouthPlans(proposer, planLimit);
        if (made >= planLimit) return made;
        List<Map<String, Object>> differences = jdbc.queryForList("""
                SELECT c.name AS carrier, m.name AS plan_name, m.base_price AS ours, s.plan_price AS theirs
                FROM mobile_plan m
                JOIN carrier c ON c.id = m.carrier_id
                JOIN LATERAL (
                    SELECT plan_price FROM smartchoice_plan_snapshot s
                    WHERE s.carrier = c.name AND s.plan_name = m.name
                    ORDER BY s.contract_months ASC, s.collected_at DESC LIMIT 1) s ON TRUE
                WHERE m.active AND abs(s.plan_price - m.base_price) > ?
                ORDER BY abs(s.plan_price - m.base_price) DESC
                LIMIT ?""", TOLERANCE_WON, planLimit - made);

        for (Map<String, Object> row : differences) {
            String key = row.get("carrier") + "|" + row.get("plan_name");
            if (pending("mobile_plan", key)) continue;
            String price = String.valueOf(((Number) row.get("theirs")).longValue());
            if (propose(proposer, CatalogAuditLog.Action.UPDATE, "mobile_plan", key, Map.of("base_price", price),
                    "일일 수집(스마트초이스): 기존 " + row.get("ours") + "원 → " + price + "원")) made++;
        }
        return made;
    }

    /**
     * 공식 기본 행과 정확히 대응되는 KT Y덤·SKT (청년) 결손만 CREATE 제안으로 올린다(G-55).
     * 다른 결손은 음성·문자·가입 조건을 알 수 없으므로 자동 승격하지 않는다.
     */
    private int harvestMissingYouthPlans(long proposer, int limit) {
        if (limit <= 0) return 0;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DISTINCT ON (g.id) g.id AS candidate_id,
                       btrim(s.carrier) AS carrier, btrim(s.plan_name) AS plan_name,
                       s.plan_price::text AS base_price, s.display_data,
                       CASE m.network_type
                           WHEN 'FIVE_G' THEN '5G' WHEN 'THREE_G' THEN '3G'
                           WHEN 'LTE_5G' THEN '5G/LTE' ELSE m.network_type END AS network_type,
                       coalesce(m.voice_min::text, '') AS voice_min,
                       coalesce(m.sms_cnt::text, '') AS sms_cnt,
                       coalesce(m.contract_discount_12m::text, '') AS contract_discount_12m,
                       coalesce(m.contract_discount_24m::text, '') AS contract_discount_24m,
                       m.source_url, m.collected_at::text AS collected_at
                  FROM catalog_candidate g
                  JOIN smartchoice_plan_snapshot s
                    ON g.query_text = btrim(s.carrier) || ' ' || btrim(s.plan_name)
                  JOIN carrier c
                    ON replace(lower(btrim(c.name)), ' ', '') = replace(lower(btrim(s.carrier)), ' ', '')
                  JOIN mobile_plan m
                    ON m.carrier_id = c.id AND m.active AND m.name = CASE
                        WHEN btrim(s.plan_name) LIKE '% Y덤'
                            THEN left(btrim(s.plan_name), length(btrim(s.plan_name)) - length(' Y덤'))
                        WHEN btrim(s.plan_name) LIKE '% (청년)'
                            THEN left(btrim(s.plan_name), length(btrim(s.plan_name)) - length(' (청년)'))
                        END
                 WHERE g.kind = 'MOBILE_PLAN' AND g.status IN ('REQUESTED', 'IN_PROGRESS')
                   AND ((replace(upper(btrim(s.carrier)), ' ', '') = 'KT'
                         AND btrim(s.plan_name) LIKE '% Y덤')
                     OR (replace(upper(btrim(s.carrier)), ' ', '') = 'SKT'
                         AND btrim(s.plan_name) LIKE '% (청년)'))
                   AND s.contract_months = 0
                   AND NOT EXISTS (
                       SELECT 1 FROM mobile_plan target
                        WHERE target.carrier_id = c.id AND target.name = btrim(s.plan_name))
                 ORDER BY g.id, s.collected_at DESC
                 LIMIT ?
                """, limit);

        int made = 0;
        for (Map<String, Object> row : rows) {
            Long dataMb = dataMb((String) row.get("display_data"));
            if (dataMb == null) continue;
            var values = new LinkedHashMap<String, String>();
            for (String field : List.of("carrier", "plan_name", "network_type", "base_price", "voice_min",
                    "sms_cnt", "contract_discount_12m", "contract_discount_24m", "source_url", "collected_at"))
                values.put(field, String.valueOf(row.get(field)));
            values.put("data_mb", String.valueOf(dataMb));
            values.put("age_limit", "청년");
            String key = values.get("carrier") + "|" + values.get("plan_name");
            if (pending("mobile_plan", key)) continue;
            if (propose(proposer, CatalogAuditLog.Action.CREATE, "mobile_plan", key, values,
                    "일일 수집(스마트초이스): 공식 기본 행의 청년 파생형")) {
                jdbc.update("UPDATE catalog_candidate SET status = 'PENDING', updated_at = now() WHERE id = ?",
                        row.get("candidate_id"));
                made++;
            }
        }
        return made;
    }

    /** 스마트초이스의 "42GB + 1Mbps" 형식에서 월 기본 제공량만 MB로 읽는다. */
    private static Long dataMb(String display) {
        if (display == null) return null;
        if (display.strip().startsWith("무제한")) return 999999L;
        var match = DATA_AMOUNT.matcher(display);
        if (!match.find()) return null;
        try {
            BigDecimal amount = new BigDecimal(match.group(1));
            if ("GB".equalsIgnoreCase(match.group(2))) amount = amount.multiply(BigDecimal.valueOf(1024));
            return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    /**
     * 구독 등급의 공식 표기가를 확인해 우리 값과 다른 것만 제안으로 만든다(D-60).
     *
     * <p><b>URL 드리프트 가드.</b> 저쪽이 읽은 {@code sourceUrl} 이 우리 {@code official_url} 과 다르면
     * <b>그 서비스는 통째로 건너뛴다.</b> 같은 URL 이 두 레포에 따로 살아 있는 구조라(저쪽은 SSRF 때문에
     * 요청으로 URL 을 받지 않는다), 우리가 카탈로그에서 주소를 고쳤는데 저쪽이 옛 페이지를 계속 읽는 날
     * <b>엉뚱한 페이지의 가격이 제안으로 올라온다.</b> 한 줄로 막을 수 있는 자리다.
     *
     * <p>원화·월 결제 등급만 본다. 해외 결제 등급은 우리 {@code price} 가 그 통화의 표기가라 비교 대상이
     * 아니고, 연 결제는 월 정가가 아니다.
     */
    private int harvestSubscriptions(long proposer) {
        int made = 0;
        for (String serviceName : subscriptionServices) {
            if (made >= subscriptionLimit) break;
            List<Map<String, Object>> ours = jdbc.queryForList("""
                    SELECT t.id, t.name, t.price, s.official_url
                    FROM subscription_tier t JOIN subscription_service s ON s.id = t.service_id
                    WHERE t.active AND s.active AND s.name = ? AND t.currency = 'KRW'""", serviceName);
            if (ours.isEmpty()) {
                log.warn("구독 공식가 조회 건너뜀 — 카탈로그에 원화 등급이 없다: {}", serviceName);
                continue;
            }
            SubscriptionPriceOracle.Check check;
            try {
                check = subscriptions.check(serviceName);
            } catch (SubscriptionPriceOracle.Unavailable e) {
                // 삼키되 침묵하지 않는다. CATALOG-SOURCE-CHANGED 는 장애가 아니라 사람이 봐야 한다는 신호라
                // 로그로 끝내면 다음 날 아무도 모른다 — 운영 타임라인(`/admin/audit`)에 남긴다.
                log.warn("구독 공식가 조회 실패 {} [{}]: {}", serviceName, e.code(), e.getMessage());
                actions.record(proposer, "SUBSCRIPTION_CHECK_FAILED", serviceName, e.code() + ": " + e.getMessage());
                continue;
            }
            String officialUrl = String.valueOf(ours.get(0).get("official_url"));
            if (!officialUrl.equals(check.sourceUrl())) {
                log.warn("구독 공식가 출처 불일치 — 제안하지 않는다. {}: 카탈로그 {} ≠ 조회 {}",
                        serviceName, officialUrl, check.sourceUrl());
                actions.record(proposer, "SUBSCRIPTION_SOURCE_DRIFT", serviceName,
                        "카탈로그 " + officialUrl + " ≠ 조회 " + check.sourceUrl());
                continue;
            }
            for (SubscriptionPriceOracle.Offer offer : check.offers()) {
                if (made >= subscriptionLimit) break;
                if (!"KRW".equals(offer.currency()) || !"MONTH".equals(offer.billingPeriod())) continue;
                Map<String, Object> tier = ours.stream()
                        .filter(row -> offer.tierName().equals(row.get("name"))).findFirst().orElse(null);
                if (tier == null) continue;   // 등급 이름이 안 붙으면 제안하지 않는다 — 우리가 짝을 지어내지 않는다
                long stored = ((Number) tier.get("price")).longValue();
                if (Math.abs(offer.price() - stored) <= SUBSCRIPTION_TOLERANCE_WON) continue;
                String key = String.valueOf(tier.get("id"));
                if (pending("subscription_tier", key)) continue;
                if (propose(proposer, CatalogAuditLog.Action.UPDATE, "subscription_tier", key,
                        Map.of("price", String.valueOf(offer.price())),
                        "일일 수집(" + serviceName + " 공식 페이지): 기존 " + stored + "원 → " + offer.price()
                                + "원. 원문: " + offer.evidence())) made++;
            }
        }
        return made;
    }

    /**
     * 기간 한정 특가를 공식 페이지로 다시 확인해 달라진 것만 제안한다(§9, 2026-09-21).
     *
     * <p>13행을 한 번 손으로 채운 상태였다 — 특가는 <b>끝나는 것이 정상</b>이라 그대로 두면 곧 어긋난다.
     * 대상은 이미 특가로 표시된 요금제뿐이다. <b>새 특가를 찾지 않는다</b>: 저쪽이 목록을 훑지 않고
     * (봇 차단), 발견은 원래 사람의 검수 절차다.
     *
     * <p><b>{@code failures} 에 오른 번호는 손대지 않는다.</b> 못 읽은 것과 특가가 끝난 것은 다르다 —
     * 행이 사라진 것으로 보고 지우면 멀쩡한 특가가 조용히 없어진다. 그래서 여기서는 <b>지우는 제안을
     * 아예 만들지 않는다</b>: 특가 종료는 사람이 합본에서 행을 빼는 것으로만 일어난다.
     */
    private int harvestPromotions(long proposer) {
        List<Map<String, Object>> ours = jdbc.queryForList("""
                SELECT p.id, p.name, p.promo_months, p.regular_price, p.source_url, c.name AS carrier
                  FROM mobile_plan p JOIN carrier c ON c.id = p.carrier_id
                 WHERE p.active AND p.promo_months IS NOT NULL
                 ORDER BY p.id""");
        if (ours.isEmpty()) {
            return 0;
        }
        var byProduct = new LinkedHashMap<Long, Map<String, Object>>();
        for (Map<String, Object> row : ours) {
            com.palsaekjo.yogobi.catalog.PlanPromotionOracle.productId(String.valueOf(row.get("source_url")))
                    .ifPresent(id -> byProduct.put(id, row));
        }
        if (byProduct.isEmpty()) {
            log.warn("특가 조회 건너뜀 — 출처 URL 에서 상품 번호를 읽지 못했다({}건)", ours.size());
            return 0;
        }
        int made = 0;
        for (List<Long> batch : partition(List.copyOf(byProduct.keySet()),
                com.palsaekjo.yogobi.catalog.PlanPromotionOracle.MAX_PRODUCTS)) {
            com.palsaekjo.yogobi.catalog.PlanPromotionOracle.Check check;
            try {
                check = promotions.check(batch);
            } catch (com.palsaekjo.yogobi.catalog.PlanPromotionOracle.Unavailable e) {
                // 삼키되 침묵하지 않는다 — 운영 타임라인에 남겨야 다음 날 누군가 본다(§8 과 같은 이유).
                log.warn("특가 조회 실패 [{}]: {}", e.code(), e.getMessage());
                actions.record(proposer, "PROMOTION_CHECK_FAILED", String.valueOf(batch.size()),
                        e.code() + ": " + e.getMessage());
                continue;
            }
            for (var failure : check.failures()) {
                // 못 읽은 것은 기록만 한다. 기존 행은 그대로 둔다.
                actions.record(proposer, "PROMOTION_ROW_FAILED", String.valueOf(failure.productId()), failure.code());
            }
            for (var promotion : check.promotions()) {
                Map<String, Object> row = byProduct.get(promotion.productId());
                if (row == null) continue;   // 안 물어본 번호는 무시한다 — 우리가 짝을 지어내지 않는다
                if (!String.valueOf(row.get("name")).equals(promotion.planName())
                        || !String.valueOf(row.get("carrier")).equals(promotion.carrier())) {
                    // 이름이 어긋나면 제안하지 않는다. 다른 상품의 값을 우리 행에 넣을 수는 없다.
                    actions.record(proposer, "PROMOTION_NAME_DRIFT", String.valueOf(promotion.productId()),
                            "카탈로그 " + row.get("carrier") + " " + row.get("name")
                                    + " ≠ 조회 " + promotion.carrier() + " " + promotion.planName());
                    continue;
                }
                Integer storedMonths = (Integer) row.get("promo_months");
                Long storedRegular = (Long) row.get("regular_price");
                if (promotion.promoMonths() == (storedMonths == null ? -1 : storedMonths)
                        && storedRegular != null && storedRegular == promotion.regularPrice()) {
                    continue;   // 그대로다
                }
                String key = row.get("carrier") + "|" + row.get("name");
                if (pending("mobile_plan_promo", key)) continue;
                if (propose(proposer, CatalogAuditLog.Action.UPDATE, "mobile_plan_promo", key,
                        Map.of("promo_months", String.valueOf(promotion.promoMonths()),
                                "regular_price", String.valueOf(promotion.regularPrice())),
                        "일일 수집(특가 페이지): " + storedMonths + "개월/" + storedRegular + "원 → "
                                + promotion.promoMonths() + "개월/" + promotion.regularPrice()
                                + "원. 원문: " + promotion.evidence())) made++;
            }
        }
        return made;
    }

    /** 계약 상한(30개)에 맞춰 나눈다. 한 번에 다 보내면 저쪽이 거절한다. */
    private static List<List<Long>> partition(List<Long> all, int size) {
        var out = new ArrayList<List<Long>>();
        for (int from = 0; from < all.size(); from += size) {
            out.add(all.subList(from, Math.min(from + size, all.size())));
        }
        return out;
    }

    /** 같은 대상의 대기 중 제안이 있으면 또 만들지 않는다 — 매일 같은 줄이 쌓이면 검수함이 못 쓰게 된다. */
    private boolean pending(String dataset, String key) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM catalog_change_request
                WHERE status = 'PENDING' AND dataset = ? AND row_key = ?""", Long.class, dataset, key);
        return count != null && count > 0;
    }

    private boolean propose(long proposer, CatalogAuditLog.Action action, String dataset, String key,
                            Map<String, String> values, String reason) {
        try {
            requests.propose(proposer, action, dataset, key, values, reason);
            return true;
        } catch (RuntimeException e) {
            log.warn("수집 제안 실패 — 건너뜀: {} {} ({})", dataset, key, e.getClass().getSimpleName());
            return false;
        }
    }
}
