package com.palsaekjo.yogobi.catalog;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/** G-89 f. 공개 목록은 짧게 기억한다 — 요청마다 1,700행 조인을 다시 하지 않는다. */
class CatalogControllerMemoTest {

    @Test
    void repeatedCallsWithinTheWindowReadTheCatalogOnce() {
        var reader = mock(CatalogReader.class);
        when(reader.listPlans()).thenReturn(List.of());
        when(reader.listServices()).thenReturn(List.of());
        var controller = new CatalogController(reader, 60);

        for (int i = 0; i < 5; i++) {
            controller.plans();
            controller.services();
        }

        verify(reader, times(1)).listPlans();
        verify(reader, times(1)).listServices();
    }

    @Test
    void aZeroWindowAlwaysReads() {
        var reader = mock(CatalogReader.class);
        when(reader.listPlans()).thenReturn(List.of());
        var controller = new CatalogController(reader, 0);

        controller.plans();
        controller.plans();

        verify(reader, times(2)).listPlans();
    }

    /** G-91. 만료 순간 동시에 몰린 요청도 DB 는 한 번만 읽는다. */
    @Test
    void concurrentMissesReadTheCatalogOnce() throws Exception {
        var reader = mock(CatalogReader.class);
        when(reader.listPlans()).thenAnswer(i -> { Thread.sleep(50); return List.of(); });
        var controller = new CatalogController(reader, 60);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var calls = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < 8; i++) calls.add(pool.submit(() -> { start.await(); return controller.plans(); }));
        start.countDown();
        for (var call : calls) call.get();
        pool.shutdown();

        verify(reader, times(1)).listPlans();
    }
}
