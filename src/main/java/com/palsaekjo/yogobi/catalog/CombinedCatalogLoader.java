package com.palsaekjo.yogobi.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 시작 시 합본 CSV(단일 원본)를 DB에 반영한다. D-21.
 * 경로가 설정돼 있을 때만 동작하며, 그 경우 {@link CatalogSeedLoader}의 개별 시드 적재는 건너뛴다.
 * 외부 관리 모드(`CATALOG_CSV_DIR`)가 켜져 있으면 그쪽(`CatalogCsvSync`)이 원본이므로 관여하지 않는다.
 */
@Component
@Order(2)
public class CombinedCatalogLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CombinedCatalogLoader.class);
    private final CombinedCatalogStore store;
    private final String catalogDirectory;

    public CombinedCatalogLoader(CombinedCatalogStore store, @Value("${CATALOG_CSV_DIR:}") String catalogDirectory) {
        this.store = store;
        this.catalogDirectory = catalogDirectory;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!store.enabled() || !catalogDirectory.isBlank()) return;
        store.reload();
        log.info("합본 CSV 카탈로그 반영 완료");
    }
}
