package com.palsaekjo.yogobi.admin;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.palsaekjo.yogobi.catalog.ExchangeRates;
import com.palsaekjo.yogobi.privacy.RetentionService;
import com.palsaekjo.yogobi.recommend.SmartChoiceSweepService;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** G-79. 예약 작업의 성공·실패가 운영 타임라인(admin_action)에 남는다. */
class ScheduledJobsTest {
    private final CatalogDailyHarvest harvest = mock(CatalogDailyHarvest.class);
    private final SmartChoiceSweepService sweep = mock(SmartChoiceSweepService.class);
    private final ExchangeRates fx = mock(ExchangeRates.class);
    private final RetentionService retention = mock(RetentionService.class);
    private final AdminActions actions = mock(AdminActions.class);
    private final ScheduledJobs jobs = new ScheduledJobs(harvest, sweep, fx, retention, actions);

    /** a. 성공은 결과 요약과 함께 남는다. */
    @Test void successIsRecordedWithTheResult() {
        when(retention.purge()).thenReturn(Map.of("payment_record", 3));
        jobs.purge();
        verify(actions).record(eq(0L), eq("SCHEDULED_RETENTION"), eq("성공"), contains("payment_record=3"));
    }

    /** b. 예외는 실패로 남고 삼켜진다 — 다음 작업이 계속 돈다. */
    @Test void failureIsRecordedAndSwallowed() {
        when(harvest.harvest()).thenThrow(new IllegalStateException("스냅샷 없음"));
        jobs.harvest();
        verify(actions).record(eq(0L), eq("SCHEDULED_HARVEST"), eq("실패"), contains("스냅샷 없음"));
    }

    /** c. 예외 없이 "갱신 안 됨"을 돌려주는 것도 실패다. */
    @Test void quietFailureIsStillAFailure() {
        when(fx.refreshNow()).thenReturn(Map.of("updated", false));
        jobs.exchangeRates();
        verify(actions).record(eq(0L), eq("SCHEDULED_FX"), eq("실패"), contains("updated=false"));
    }

    @Test void sweepIsRecordedToo() {
        when(sweep.sweep()).thenReturn(Map.of("conditions", 60));
        jobs.smartChoice();
        verify(actions).record(eq(0L), eq("SCHEDULED_SMARTCHOICE"), eq("성공"), contains("conditions=60"));
    }
}
