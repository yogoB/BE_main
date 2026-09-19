package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.common.ApiResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 랜딩의 "이용자들이 진단에서 확인한 절감액"(D-53). 공개 경로 — 인증도 CSRF 도 없다.
 *
 * <p><b>금액만 나간다.</b> 계정·요금제·시각은 싣지 않는다. 표본은 <b>로그인한 채 결과 화면을 본 회원</b>이고
 * (D-59), 계정당 최신 1건이다 — 여러 번 봐도 한 번만 센다. 저장 버튼을 누른 회원만 세던 때는(D-53)
 * 표본이 거의 안 쌓여 랜딩이 늘 임계값 미달이었다. 기준은 그대로 <b>지금 쓰는 요금제 대비</b>다 —
 * 정가 대비 값은 알뜰폰에서 대부분 0 이라 화면에 0 만 늘어놓게 된다.
 *
 * <p>표본이 {@link #MIN_SAMPLES} 미만이면 <b>빈 배열</b>을 준다. 화면은 이때 숫자 블록을 숨긴다
 * (가짜 숫자를 넣지 않는다). 임계값은 5 였다가 <b>2 로 내렸다</b>(2026-09-20, 사용자 결정) —
 * 5 로는 서비스 초기에 랜딩이 계속 비어 있었다. 대신 <b>표본 수는 화면에 적지 않는다</b>:
 * "이용자 2명 기준"은 평균이라는 이름으로 두 사람의 금액을 거의 그대로 알려 주는 셈이다.
 *
 * <p>우리가 아는 것은 "진단에서 확인한 절감액"이지 실제로 옮겼는지가 아니다. 화면 문구도 그렇게 적는다.
 */
@RestController
@RequestMapping("/api/v1/stats")
public class SavingsStatsController {
    /** 이보다 적으면 노출하지 않는다. 2026-09-20 에 5 → 2(사용자 결정) — 화면은 표본 수를 적지 않는다. */
    static final int MIN_SAMPLES = 2;
    /** 랜딩이 순환 표시하는 개수. 더 줘도 화면이 쓰지 않는다. */
    private static final int MAX_SAMPLES = 30;
    private final JdbcTemplate jdbc;
    /** 방문마다 집계하지 않는다. 짧게 잡아 새 표본이 곧 반영되게 한다. 0 이면 매번 집계(테스트). */
    private final Duration cache;
    private volatile Savings cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public SavingsStatsController(JdbcTemplate jdbc,
                                  @org.springframework.beans.factory.annotation.Value("${yogobi.stats.cache-seconds:60}") long cacheSeconds) {
        this.jdbc = jdbc;
        this.cache = Duration.ofSeconds(cacheSeconds);
    }

    /**
     * {@code basis} 는 이 금액이 무엇 대비인지다 — 화면 문구가 기준을 적을 수 있어야 한다(절대 원칙 4).
     * {@code monthlyAverage}·{@code monthlyMedian} 은 랜딩의 "1인당 절감액"이다(D-57). 표본이 임계값
     * 미만이면 {@code samples} 와 함께 <b>둘 다 null</b> — 한두 명의 금액을 평균이라는 이름으로 내보내지 않는다.
     */
    public record Savings(List<Long> samples, int sampleCount, Long monthlyAverage, Long monthlyMedian,
                          String basis, Instant updatedAt) { }

    @GetMapping("/savings")
    public ApiResponse<Savings> savings() {
        Savings snapshot = cached;
        if (snapshot == null || cachedAt.isBefore(Instant.now().minus(cache))) {
            snapshot = collect();
            cached = snapshot;
            cachedAt = Instant.now();
        }
        return ApiResponse.ok(snapshot);
    }

    private Savings collect() {
        // 계정당 한 행이다(테이블이 보장한다). 0 이하(더 내는 조합)는 "절감액 표본"이 아니므로 뺀다.
        List<Long> samples = jdbc.queryForList("""
                SELECT monthly_savings FROM member_savings
                WHERE monthly_savings > 0 ORDER BY seen_at DESC LIMIT ?
                """, Long.class, MAX_SAMPLES);
        int count = samples.size();
        if (count < MIN_SAMPLES) {
            return new Savings(List.of(), count, null, null, "CURRENT_PLAN", Instant.now());
        }
        long average = Math.round(samples.stream().mapToLong(Long::longValue).average().orElse(0));
        var sorted = samples.stream().sorted().toList();
        long median = sorted.size() % 2 == 1 ? sorted.get(sorted.size() / 2)
                : Math.round((sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0);
        return new Savings(samples, count, average, median, "CURRENT_PLAN", Instant.now());
    }
}
