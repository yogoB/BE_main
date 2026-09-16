package com.palsaekjo.yogobi.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * 스윕은 배치 전용이다 — 여기서 지키는 것은 D-05(요청 경로 외부 미호출)가 아니라 그 배치의 fail-soft 다.
 * 키가 없으면 아무것도 하지 않고, 서버에 닿지 못하면 남은 격자를 건너뛴다(이전 스냅샷 유지).
 */
class SmartChoiceSweepServiceTest {

    /** 호출 결과를 정해 두는 클라이언트. 실제 HTTP 는 타지 않는다. */
    private static class StubClient extends SmartChoiceClient {
        private final boolean enabled;
        private final RuntimeException failure;
        private final List<SmartChoiceRecommendation> found;
        int calls;

        StubClient(boolean enabled, RuntimeException failure, List<SmartChoiceRecommendation> found) {
            super(RestClient.builder(), enabled ? "key" : "", "http://localhost:1");
            this.enabled = enabled;
            this.failure = failure;
            this.found = found;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public List<SmartChoiceRecommendation> recommend(int dataMb, int voiceMin, int sms, int age, int type, int dis) {
            calls++;
            if (failure != null) throw failure;
            return found;
        }
    }

    private static JdbcTemplate jdbcWith(int conditions) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(
                java.util.stream.IntStream.range(0, conditions)
                        .<Map<String, Object>>mapToObj(i -> Map.of("network_type", "FIVE_G", "data_mb", 30720L))
                        .toList());
        return jdbc;
    }

    @Test
    void 키가_없으면_외부를_부르지도_저장하지도_않는다() {
        var client = new StubClient(false, null, List.of());
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new SmartChoiceSweepService(client, jdbc, 60).sweep();
        assertThat(client.calls).isZero();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void 도달하지_못하면_남은_격자를_건너뛴다() {
        var client = new StubClient(true, new SmartChoiceClient.Unreachable(), List.of());
        var jdbc = jdbcWith(5);
        new SmartChoiceSweepService(client, jdbc, 60).sweep();
        assertThat(client.calls).isEqualTo(1);                       // 첫 실패에서 멈춘다
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void 받은_시세를_스냅샷에_저장한다() {
        var client = new StubClient(true, null,
                List.of(new SmartChoiceRecommendation(1, "SKT", "5G 시그니처", 89_000L, 66_750L, "30GB")));
        var jdbc = jdbcWith(2);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        new SmartChoiceSweepService(client, jdbc, 60).sweep();
        assertThat(client.calls).isEqualTo(2);
        verify(jdbc, org.mockito.Mockito.times(2)).update(contains("INSERT INTO smartchoice_plan_snapshot"), any(Object[].class));
    }

    @Test
    void 통신사나_요금제명이_비면_대조_키가_없으므로_버린다() {
        var client = new StubClient(true, null,
                List.of(new SmartChoiceRecommendation(1, "  ", "5G 시그니처", 89_000L, 0, "30GB")));
        var jdbc = jdbcWith(1);
        new SmartChoiceSweepService(client, jdbc, 60).sweep();
        verify(jdbc, never()).update(contains("INSERT INTO smartchoice_plan_snapshot"), any(Object[].class));
    }
}
