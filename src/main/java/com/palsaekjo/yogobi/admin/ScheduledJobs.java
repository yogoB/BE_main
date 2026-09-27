package com.palsaekjo.yogobi.admin;

import com.palsaekjo.yogobi.catalog.ExchangeRates;
import com.palsaekjo.yogobi.privacy.PrivacyPolicy;
import com.palsaekjo.yogobi.privacy.RetentionService;
import com.palsaekjo.yogobi.recommend.SmartChoiceSweepService;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 예약 작업을 한 곳에서 돌리고 <b>결과를 운영 타임라인(admin_action)에 남긴다</b>(G-79).
 *
 * <p>전엔 각 서비스가 스스로 예약돼 있었고 수동 실행만 기록됐다. 예약 실행은 성공도 실패도 타임라인에 없었고,
 * 개인정보 파기가 며칠째 실패해도 아무도 몰랐다. 또 파기는 같은 클래스 안에서 {@code purge()} 를 불러
 * {@code @Transactional} 이 적용되지 않았다 — 여기서는 빈을 거쳐 부르므로 적용된다.
 *
 * <p>cron 속성 이름은 그대로다. {@code actor_id=0} 은 "시스템"이다(회원 id 는 1부터).
 */
@Component
public class ScheduledJobs {
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);
    static final long SYSTEM = 0;

    private final CatalogDailyHarvest harvest;
    private final SmartChoiceSweepService sweep;
    private final ExchangeRates fx;
    private final RetentionService retention;
    private final AdminActions actions;

    public ScheduledJobs(CatalogDailyHarvest harvest, SmartChoiceSweepService sweep, ExchangeRates fx,
                         RetentionService retention, AdminActions actions) {
        this.harvest = harvest;
        this.sweep = sweep;
        this.fx = fx;
        this.retention = retention;
        this.actions = actions;
    }

    /** 한국시간 매일 09:00. 실패해도 다음 날 다시 돈다 — 카탈로그는 그대로 유지된다(fail-soft). */
    @Scheduled(cron = "${yogobi.harvest.cron:0 0 9 * * *}", zone = "Asia/Seoul")
    public void harvest() {
        run("SCHEDULED_HARVEST", harvest::harvest);
    }

    /** 하루 3회(D-12). 머신이 잠들어 있으면 발화하지 않으므로 화면의 수동 실행이 실질적인 경로다. */
    @Scheduled(cron = "${yogobi.smartchoice.cron:0 40 3,12,20 * * *}", zone = "Asia/Seoul")
    public void smartChoice() {
        run("SCHEDULED_SMARTCHOICE", sweep::sweep);
    }

    /** ECB 참고환율은 CET 16:00 무렵 고시되므로 한국 시간 아침에 받는다. 실패는 값을 돌려받아 판정한다. */
    @Scheduled(cron = "${yogobi.fx.cron:0 15 9 * * *}", zone = "Asia/Seoul")
    public void exchangeRates() {
        run("SCHEDULED_FX", fx::refreshNow);
    }

    /** 보유기간이 지난 개인 데이터 파기. 기본 매일 04:00. */
    @Scheduled(cron = "${yogobi.retention.cron:0 0 4 * * *}", zone = PrivacyPolicy.RETENTION_ZONE)
    public void purge() {
        run("SCHEDULED_RETENTION", retention::purge);
    }

    private void run(String action, Supplier<? extends Map<String, ?>> job) {
        try {
            Map<String, ?> result = job.get();
            // 예외 없이 "갱신 안 됨"을 돌려주는 작업(환율)도 실패로 센다 — 조용한 실패도 실패다(G-79 c).
            boolean failed = Boolean.FALSE.equals(result.get("updated"));
            actions.record(SYSTEM, action, failed ? "실패" : "성공", String.valueOf(result));
            if (failed) log.warn("예약 작업 {} 결과가 실패다: {}", action, result);
        } catch (RuntimeException e) {
            log.error("예약 작업 {} 실패 — 다음 실행에 재시도", action, e);
            actions.record(SYSTEM, action, "실패", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
