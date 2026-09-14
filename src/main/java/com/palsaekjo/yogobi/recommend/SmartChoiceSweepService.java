package com.palsaekjo.yogobi.recommend;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 스마트초이스 Open API를 사용조건 격자로 훑어 요금제 라이브 시세를 모은다(D2, data.md §2).
 * Open API는 호출당 3건만 주므로 data×type×dis 격자를 돌려 최대한 넓게 모으고 유니크 키로 dedup(upsert)한다.
 * "거의 실시간" = 하루 3회 배치(요청마다 호출하지 않는다 — 10,000회/일 제한·데모 안정, D-05 완화 D-10).
 * fail-soft: 키 미설정·개별 호출 실패는 건너뛰고 나머지로 진행한다. 스냅샷은 카탈로그(시드)를 대체하지 않는다(교차검증·시세 참고).
 */
@Service
public class SmartChoiceSweepService {
    private static final Logger log = LoggerFactory.getLogger(SmartChoiceSweepService.class);

    // 격자(정책값). 넓힐수록 커버리지↑ 호출수↑. 현재 6×3×3 = 54회/실행, 하루 3회 = 162회 (제한 10,000/일 내).
    static final int[] DATA_MB = {300, 1024, 3072, 5120, 11264, SmartChoiceClient.UNLIMITED};
    static final int[] TYPES = {2, 3, 6}; // 3G·LTE·5G
    static final int[] DIS = {0, 12, 24}; // 무약정·12·24개월
    static final int AGE = 20;            // 성인 기준(청소년 18·실버 65는 필요 시 격자 확장)
    static final int VOICE = SmartChoiceClient.UNLIMITED;
    static final int SMS = SmartChoiceClient.UNLIMITED;
    static final int MAX_CALLS = 2000;    // 격자 확장 시 폭주 방지 상한
    static final int MAX_CONSECUTIVE_UNREACHABLE = 3; // 도달성 가드: 연속 미응답 시 조기 중단(9분 헛돎 방지)

    private final SmartChoiceClient client;
    private final JdbcTemplate jdbc;
    private final String sourceUrl;

    public SmartChoiceSweepService(SmartChoiceClient client, JdbcTemplate jdbc,
                                   @Value("${SMARTCHOICE_API_URL:http://api.smartchoice.or.kr/openAPI.xml}") String sourceUrl) {
        this.client = client;
        this.jdbc = jdbc;
        this.sourceUrl = sourceUrl;
    }

    public record SweepResult(boolean enabled, int calls, int rowsUpserted) {
    }

    /** 격자 스윕 1회. 전체를 한 트랜잭션으로 묶지 않는다(HTTP I/O 중 커넥션 점유 회피) — 행별 업서트가 부분 성공을 남긴다. */
    public SweepResult sweep() {
        if (!client.enabled()) {
            log.info("스마트초이스 스윕 건너뜀 — SMARTCHOICE_API_KEY 미설정 (fail-soft)");
            return new SweepResult(false, 0, 0);
        }
        int calls = 0;
        int rows = 0;
        int consecutiveUnreachable = 0;
        outer:
        for (int type : TYPES) {
            for (int dis : DIS) {
                for (int data : DATA_MB) {
                    if (calls >= MAX_CALLS) {
                        log.warn("스마트초이스 스윕 상한 {}회 도달 — 중단", MAX_CALLS);
                        break outer;
                    }
                    calls++;
                    try {
                        List<SmartChoiceRecommendation> recs = client.recommend(data, VOICE, SMS, AGE, type, dis);
                        consecutiveUnreachable = 0;
                        for (SmartChoiceRecommendation rec : recs) {
                            rows += upsert(rec, networkName(type), dis);
                        }
                    } catch (SmartChoiceClient.Unreachable e) {
                        // 도달성 가드: 연속 미응답이면 나머지 격자를 계속 두드리지 않고 중단(등록 IP·한국망 제한 등).
                        if (++consecutiveUnreachable >= MAX_CONSECUTIVE_UNREACHABLE) {
                            log.warn("스마트초이스 연속 {}회 도달 실패 — 스윕 중단(도달성 가드). 등록 IP·한국망 확인 필요", consecutiveUnreachable);
                            break outer;
                        }
                    }
                }
            }
        }
        log.info("스마트초이스 스윕 완료 — 호출 {}회, 스냅샷 {}행 반영", calls, rows);
        return new SweepResult(true, calls, rows);
    }

    private int upsert(SmartChoiceRecommendation rec, String networkType, int contractMonths) {
        if (rec.carrier().isBlank() || rec.planName().isBlank()) {
            return 0; // 식별 불가 행은 버린다
        }
        return jdbc.update("""
                INSERT INTO smartchoice_plan_snapshot
                    (carrier, plan_name, network_type, contract_months, plan_price, discounted_price, display_data, source_url, collected_at)
                VALUES (?,?,?,?,?,?,?,?, now())
                ON CONFLICT (carrier, plan_name, network_type, contract_months)
                DO UPDATE SET plan_price=EXCLUDED.plan_price, discounted_price=EXCLUDED.discounted_price,
                    display_data=EXCLUDED.display_data, source_url=EXCLUDED.source_url, collected_at=now()
                """, rec.carrier(), rec.planName(), networkType, contractMonths,
                rec.planPrice(), rec.discountedPrice(), rec.displayData(), sourceUrl);
    }

    private static String networkName(int type) {
        return switch (type) {
            case 2 -> "3G";
            case 6 -> "5G";
            default -> "LTE";
        };
    }

    // 거의 실시간: 하루 3회(06/14/22 KST). 요청마다 호출하지 않는다.
    @Scheduled(cron = "${yogobi.smartchoice.cron:0 0 6,14,22 * * *}", zone = "Asia/Seoul")
    public void scheduledSweep() {
        sweep();
    }
}
