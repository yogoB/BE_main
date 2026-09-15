package com.palsaekjo.yogobi.catalog;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 우체국알뜰폰 API 요금제를 카탈로그 캐시(carrier·mobile_plan)에 적재한다. 시드/CSV와 같은 mobile_plan 을 upsert로 공유한다.
 * fail-soft: 키·호출 실패면 아무것도 안 바꾼다(기존 카탈로그 유지). 무효행(기본료≤0·망 미매핑·이름 없음)은 건너뛴다.
 * <p>파서 가정(무제한·단위)은 실제 응답으로 확정할 부분 — {@code amountOrUnlimited}·{@code mapNetwork} 한 곳에 모아둠.
 */
@Service
public class MvnoCatalogLoader {
    private static final Logger log = LoggerFactory.getLogger(MvnoCatalogLoader.class);
    private static final long UNLIMITED = 999999; // 시드 관례: 무제한은 999999

    private final PostOfficeMvnoClient client;
    private final JdbcTemplate jdbc;
    private final String sourceUrl;

    public MvnoCatalogLoader(PostOfficeMvnoClient client, JdbcTemplate jdbc,
                             @Value("${POST_OFFICE_MVNO_API_URL:http://openapi.epost.go.kr/postal/retrieveAlddlChargeService/retrieveAlddlChargeService/getAlddlChargeList}") String sourceUrl) {
        this.client = client;
        this.jdbc = jdbc;
        this.sourceUrl = sourceUrl;
    }

    public record LoadResult(int fetched, int loaded, int skipped) {
    }

    /** 조회는 트랜잭션 밖(HTTP), 적재는 행별 upsert(부분 성공 허용). 카탈로그 캐시라 재실행 멱등. */
    public LoadResult load() {
        List<MvnoPlan> plans = client.fetchAll();
        int loaded = 0;
        int skipped = 0;
        for (MvnoPlan p : plans) {
            String network = mapNetwork(p.networkType());
            long base = digits(p.baseFee());
            if (p.carrier().isBlank() || p.planName().isBlank() || network == null || base <= 0) {
                skipped++;
                continue;
            }
            long carrierId = carrierId(p.carrier());
            jdbc.update("""
                    INSERT INTO mobile_plan (carrier_id, name, network_type, base_price, data_mb, voice_min, sms_cnt, source_url, collected_at)
                    VALUES (?,?,?,?,?,?,?,?, CURRENT_DATE)
                    ON CONFLICT (carrier_id, name) DO UPDATE SET
                        network_type=EXCLUDED.network_type, base_price=EXCLUDED.base_price, data_mb=EXCLUDED.data_mb,
                        voice_min=EXCLUDED.voice_min, sms_cnt=EXCLUDED.sms_cnt, source_url=EXCLUDED.source_url, collected_at=EXCLUDED.collected_at
                    """, carrierId, p.planName(), network, base,
                    amountOrUnlimited(p.data()), amountOrUnlimited(p.voice()), amountOrUnlimited(p.sms()), sourceUrl);
            loaded++;
        }
        log.info("우체국알뜰폰 적재 — 조회 {}건, 적재 {}건, 건너뜀 {}건", plans.size(), loaded, skipped);
        return new LoadResult(plans.size(), loaded, skipped);
    }

    @Scheduled(cron = "${yogobi.mvno.cron:0 30 5 * * *}", zone = "Asia/Seoul")
    public void scheduledLoad() {
        load();
    }

    private long carrierId(String name) {
        jdbc.update("INSERT INTO carrier(name, carrier_type) VALUES (?, 'MVNO') ON CONFLICT (name) DO NOTHING", name);
        return jdbc.queryForObject("SELECT id FROM carrier WHERE name=?", Long.class, name);
    }

    /** 5G/LTE/3G → 스키마 enum. 매핑 불가면 null(건너뜀). "LTE/3G" 는 LTE 로 본다. */
    private static String mapNetwork(String raw) {
        if (raw == null) {
            return null;
        }
        String u = raw.toUpperCase();
        if (u.contains("5G")) {
            return "FIVE_G";
        }
        if (u.contains("LTE")) {
            return "LTE";
        }
        if (u.contains("3G")) {
            return "THREE_G";
        }
        return null;
    }

    private static long digits(String text) {
        String d = text == null ? "" : text.replaceAll("\\D", "");
        return d.isEmpty() ? 0 : Long.parseLong(d);
    }

    /** 숫자면 그 값(MB/분/건), 비어있거나 숫자가 아니면(무제한 등) 999999. 실제 응답으로 확정 필요. */
    private static long amountOrUnlimited(String text) {
        String d = text == null ? "" : text.replaceAll("\\D", "");
        return d.isEmpty() ? UNLIMITED : Long.parseLong(d);
    }
}
