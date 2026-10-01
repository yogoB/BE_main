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
}
