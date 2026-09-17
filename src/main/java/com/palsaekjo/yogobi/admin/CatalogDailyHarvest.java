package com.palsaekjo.yogobi.admin;

import com.palsaekjo.yogobi.catalog.CatalogAuditLog;
import com.palsaekjo.yogobi.catalog.CatalogChangeRequests;
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
 *   <li><b>구독</b> — AI 서버 조회(`POST /catalog/candidates`)로 현재 가격 확인.</li>
 * </ul>
 *
 * <p>사용자 요청 경로는 여전히 외부를 부르지 않는다(D-05·D-17). 외부 호출은 이 배치와 승인 절차에서만 일어난다.
 * 한 번 실행에 만드는 제안 수를 제한한다 — 소스가 흔들린 날 검수함이 수백 건으로 덮이면 아무도 검수하지 않는다.
 */
@Service
public class CatalogDailyHarvest {
    private static final Logger log = LoggerFactory.getLogger(CatalogDailyHarvest.class);
    /** 금액이 이만큼 이내로 다르면 바꿀 값이 아니다(검토 허용 오차와 같은 기준). */
    private static final long TOLERANCE_WON = 100;

    private final JdbcTemplate jdbc;
    private final CatalogChangeRequests requests;
    private final AdminAccount admin;
    private final int planLimit;

    public CatalogDailyHarvest(JdbcTemplate jdbc, CatalogChangeRequests requests, AdminAccount admin,
                               @Value("${yogobi.harvest.plan-limit:20}") int planLimit) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.admin = admin;
        this.planLimit = planLimit;
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
        // 구독 등급 갱신은 대조 소스가 AI뿐이라 D-45 로 사라졌다. 새 소스가 생기면 되살린다.
        out.put("proposedSubscriptionTiers", 0);
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
