package com.palsaekjo.yogobi.recommend;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 스마트초이스 공식 시세를 배치로 모아 {@code smartchoice_plan_snapshot} 에 남긴다(D-12).
 *
 * <p>요청마다 부르지 않는다 — 하루 3회 배치이며 사용자 요청 경로는 스냅샷만 읽는다(D-05 유지).
 * 스냅샷은 카탈로그(시드)를 <b>대체하지 않는다</b>. 교차검증·시세 참고용이다(D-12 (3)).
 *
 * <p>격자는 카탈로그에 실제 있는 (망 × 데이터) 조건에서 뽑는다. 전수 격자는 우리가 추천할 수 없는
 * 조건까지 불러 호출만 늘린다. fail-soft: 키 미설정이면 아무것도 하지 않고, 서버에 도달하지 못하면
 * 남은 격자를 건너뛴다(도달성 가드) — 시드 기반 추천은 그대로 동작한다.
 */
@Component
public class SmartChoiceSweepService {
    private static final Logger log = LoggerFactory.getLogger(SmartChoiceSweepService.class);
    private static final int AGE = 20;               // 성인 기준
    private static final int CONTRACT_MONTHS = 0;    // 무약정 정가 — 카탈로그 base_price 와 같은 기준
    private static final String SOURCE_URL = "https://www.smartchoice.or.kr/";
    /** 결손 후보 상한. 닿으면 기록만 멈추고 스윕은 끝까지 간다 — CatalogCandidateRecorder 와 같은 값. */
    private static final int MAX_CANDIDATES = 10_000;

    private final SmartChoiceClient client;
    private final JdbcTemplate jdbc;
    private final int maxConditions;

    public SmartChoiceSweepService(SmartChoiceClient client, JdbcTemplate jdbc,
                                   @Value("${yogobi.smartchoice.max-conditions:60}") int maxConditions) {
        this.client = client;
        this.jdbc = jdbc;
        this.maxConditions = maxConditions;
    }

    /** 하루 3회(D-12). 머신이 잠들어 있으면 발화하지 않으므로 화면의 수동 실행이 실질적인 경로다. */
    @Scheduled(cron = "${yogobi.smartchoice.cron:0 40 3,12,20 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        try {
            log.info("스마트초이스 스윕 완료: {}", sweep());
        } catch (RuntimeException e) {
            log.warn("스마트초이스 스윕 실패 — 이전 스냅샷을 유지합니다 ({})", e.getClass().getSimpleName());
        }
    }

    /**
     * 스윕 1회. 운영자가 화면에서 결과를 읽고 다음 행동을 정할 수 있게 셋을 구분해 돌려준다 —
     * 키가 없다 / 서버에 닿지 못했다 / 돌았고 몇 행을 남겼다.
     * 닿지 못한 것은 등록 IP·한국망 제한일 수 있어 "실패"와 다르게 읽어야 한다.
     */
    public Map<String, Object> sweep() {
        var out = new LinkedHashMap<String, Object>();
        if (!client.enabled()) {
            log.info("스마트초이스 키가 없어 스윕을 건너뜁니다 (시드 추천은 그대로 동작)");
            out.put("enabled", false);
            out.put("conditions", 0);
            out.put("stored", 0);
            out.put("reachable", true);
            out.put("recordedGaps", -1);
            out.put("snapshotRows", snapshotRows());
            return out;
        }
        List<Map<String, Object>> conditions = jdbc.queryForList("""
                SELECT DISTINCT network_type, data_mb
                  FROM mobile_plan
                 ORDER BY network_type, data_mb
                 LIMIT ?
                """, maxConditions);
        int stored = 0;
        boolean reachable = true;
        for (Map<String, Object> condition : conditions) {
            int dataMb = (int) Math.min(((Number) condition.get("data_mb")).longValue(), SmartChoiceClient.UNLIMITED);
            String networkType = String.valueOf(condition.get("network_type"));
            try {
                for (SmartChoiceRecommendation found : client.recommend(dataMb, SmartChoiceClient.UNLIMITED,
                        SmartChoiceClient.UNLIMITED, AGE, networkCode(networkType), CONTRACT_MONTHS)) {
                    stored += store(found, networkType);
                }
            } catch (SmartChoiceClient.Unreachable e) {
                // 등록 IP·한국망 제한 등으로 서버에 닿지 못했다. 남은 격자도 같을 테니 여기서 멈춘다.
                log.warn("스마트초이스에 도달하지 못해 스윕을 중단합니다 — 이전 스냅샷을 유지합니다");
                reachable = false;
                break;
            }
        }
        log.info("스마트초이스 스윕: 조건 {}건, 스냅샷 {}행 갱신, 도달 {}", conditions.size(), stored, reachable);
        out.put("enabled", true);
        out.put("conditions", conditions.size());
        out.put("stored", stored);
        out.put("reachable", reachable);
        // 닿지 못한 스윕은 스냅샷을 갱신하지 못했다. 낡은 스냅샷으로 결손을 새로 적지 않는다 —
        // -1 은 "확인 못 함"이며 0("없음")과 구분한다.
        out.put("recordedGaps", reachable ? recordMissingPlans() : -1);
        out.put("snapshotRows", snapshotRows());
        return out;
    }

    /**
     * 스냅샷에 있는데 카탈로그에 없는 요금제를 결손으로 남긴다(G-19 · D-31).
     *
     * <p>스윕은 {@link #AGE} = 20 으로 돌기 때문에 청년 요금제(KT Y덤·SKT 라이트(청년)·LGU+ 유쓰)를
     * 이미 받아오고 있었다. 그 결과가 스냅샷에만 쌓이고 아무도 보지 않아 Y덤 11종이 통째로 빠져 있었다.
     * <p><b>카탈로그로 승격하지 않는다</b> — 스냅샷은 여전히 원본이 아니다(D-20). 여기 쌓인 행은
     * 사람이 CSV 에 반영해야 카탈로그가 된다(D-18). 계산에는 어느 단계에서도 쓰이지 않는다.
     * <p>fail-soft: 기록이 실패해도 이미 저장한 스냅샷은 유지한다. 그래서 실패는 -1(알 수 없음)로 돌려준다.
     */
    private int recordMissingPlans() {
        try {
            // requested_cnt 는 건드리지 않는다 — 그 값은 "사용자가 몇 번 찾았나"이고 곧 수집 우선순위다.
            // 하루 3회 배치가 올리면 아무도 찾지 않은 요금제가 우선순위 1위가 된다(G-19-d).
            int recorded = jdbc.update("""
                    INSERT INTO catalog_candidate (kind, query_text, status)
                    SELECT 'MOBILE_PLAN', btrim(s.carrier) || ' ' || btrim(s.plan_name), 'REQUESTED'
                      FROM smartchoice_plan_snapshot s
                     WHERE length(btrim(s.carrier) || ' ' || btrim(s.plan_name)) <= 200
                       AND NOT EXISTS (
                           SELECT 1 FROM mobile_plan p JOIN carrier c ON c.id = p.carrier_id
                            WHERE replace(lower(btrim(c.name)), ' ', '') = replace(lower(btrim(s.carrier)), ' ', '')
                              AND replace(lower(btrim(p.name)), ' ', '') = replace(lower(btrim(s.plan_name)), ' ', ''))
                       AND (SELECT count(*) FROM catalog_candidate) < ?
                    ON CONFLICT (kind, query_text) DO UPDATE SET last_requested_at = now()
                    """, MAX_CANDIDATES);
            log.info("카탈로그 결손 후보 {}행 기록 (사람이 CSV 에 반영해야 카탈로그가 된다)", recorded);
            return recorded;
        } catch (RuntimeException e) {
            log.warn("결손 후보 기록 실패 — 스냅샷은 유지합니다 (fail-soft): {}", e.toString());
            return -1;
        }
    }

    /** 대조에 실제로 쓸 수 있는 행이 몇 개인지. 0 이면 결과 화면은 전부 "확인 못 했다"로 나온다. */
    private long snapshotRows() {
        Long rows = jdbc.queryForObject("SELECT count(*) FROM smartchoice_plan_snapshot", Long.class);
        return rows == null ? 0 : rows;
    }

    /** 이름·통신사가 비면 대조 키가 없으므로 버린다. 같은 키는 최신 값으로 덮는다(V7 의 dedup 키). */
    private int store(SmartChoiceRecommendation found, String networkType) {
        String carrier = found.carrier() == null ? "" : found.carrier().strip();
        String planName = found.planName() == null ? "" : found.planName().strip();
        if (carrier.isEmpty() || planName.isEmpty() || found.planPrice() < 0) {
            return 0;
        }
        return jdbc.update("""
                INSERT INTO smartchoice_plan_snapshot
                    (carrier, plan_name, network_type, contract_months, plan_price, discounted_price,
                     display_data, source_url, collected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (carrier, plan_name, network_type, contract_months) DO UPDATE SET
                    plan_price = EXCLUDED.plan_price, discounted_price = EXCLUDED.discounted_price,
                    display_data = EXCLUDED.display_data, collected_at = EXCLUDED.collected_at
                """, carrier, planName, display(networkType), CONTRACT_MONTHS,
                found.planPrice(), Math.max(found.discountedPrice(), 0), found.displayData(), SOURCE_URL);
    }

    /** 카탈로그 enum 을 스냅샷 표기로. V7 의 network_type 은 3G|LTE|5G 다. */
    private static String display(String networkType) {
        return switch (networkType == null ? "" : networkType) {
            case "FIVE_G" -> "5G";
            case "THREE_G" -> "3G";
            default -> "LTE";
        };
    }

    /** data.md §2: 3G=2 · LTE=3 · 5G=6. */
    private static int networkCode(String networkType) {
        return switch (networkType == null ? "" : networkType) {
            case "FIVE_G" -> 6;
            case "THREE_G" -> 2;
            default -> 3;
        };
    }
}
