package com.palsaekjo.yogobi.admin;

import com.palsaekjo.yogobi.catalog.CatalogAuditLog;
import com.palsaekjo.yogobi.catalog.CatalogChangeRequests;
import com.palsaekjo.yogobi.catalog.SubscriptionPriceOracle;
import com.palsaekjo.yogobi.user.AdminAccount;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final JdbcTemplate jdbc;
    private final CatalogChangeRequests requests;
    private final AdminAccount admin;
    private final SubscriptionPriceOracle subscriptions;
    private final AdminActions actions;
    private final int planLimit;
    private final int subscriptionLimit;
    /** 공식가를 확인할 서비스. 이름은 {@code subscription_service.name} 과 글자까지 같아야 붙는다. */
    private final List<String> subscriptionServices;

    public CatalogDailyHarvest(JdbcTemplate jdbc, CatalogChangeRequests requests, AdminAccount admin,
                               SubscriptionPriceOracle subscriptions, AdminActions actions,
                               @Value("${yogobi.harvest.plan-limit:20}") int planLimit,
                               @Value("${yogobi.harvest.subscription-limit:20}") int subscriptionLimit,
                               @Value("${yogobi.harvest.subscription-services:Spotify,Apple Music,iCloud+}")
                               List<String> subscriptionServices) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.admin = admin;
        this.subscriptions = subscriptions;
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
        out.put("pending", jdbc.queryForObject(
                "SELECT count(*) FROM catalog_change_request WHERE status = 'PENDING'", Long.class));
        return out;
    }

    /**
     * 스마트초이스가 우리와 다른 기본료를 말하는 요금제를 제안으로 만든다.
     * 스냅샷은 무약정(contract_months 최소) 정상가를 기준으로 본다 — 우리 `base_price` 와 같은 성격의 값이다.
     */
    private int harvestPlans(long proposer) {
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
                LIMIT ?""", TOLERANCE_WON, planLimit);

        int made = 0;
        for (Map<String, Object> row : differences) {
            String key = row.get("carrier") + "|" + row.get("plan_name");
            if (pending("mobile_plan", key)) continue;
            String price = String.valueOf(((Number) row.get("theirs")).longValue());
            if (propose(proposer, "mobile_plan", key, Map.of("base_price", price),
                    "일일 수집(스마트초이스): 기존 " + row.get("ours") + "원 → " + price + "원")) made++;
        }
        return made;
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
                if (propose(proposer, "subscription_tier", key, Map.of("price", String.valueOf(offer.price())),
                        "일일 수집(" + serviceName + " 공식 페이지): 기존 " + stored + "원 → " + offer.price()
                                + "원. 원문: " + offer.evidence())) made++;
            }
        }
        return made;
    }

    /** 같은 대상의 대기 중 제안이 있으면 또 만들지 않는다 — 매일 같은 줄이 쌓이면 검수함이 못 쓰게 된다. */
    private boolean pending(String dataset, String key) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM catalog_change_request
                WHERE status = 'PENDING' AND dataset = ? AND row_key = ?""", Long.class, dataset, key);
        return count != null && count > 0;
    }

    private boolean propose(long proposer, String dataset, String key, Map<String, String> values, String reason) {
        try {
            requests.propose(proposer, CatalogAuditLog.Action.UPDATE, dataset, key, values, reason);
            return true;
        } catch (RuntimeException e) {
            log.warn("수집 제안 실패 — 건너뜀: {} {} ({})", dataset, key, e.getClass().getSimpleName());
            return false;
        }
    }
}
