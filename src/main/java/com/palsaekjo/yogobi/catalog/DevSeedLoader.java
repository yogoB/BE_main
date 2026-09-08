package com.palsaekjo.yogobi.catalog;

import java.io.IOException;
import java.sql.SQLException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 로컬 개발용 더미 요금제·혜택 시드. dev 프로파일에서만 동작한다
 * (`SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun`). 테스트는 dev 프로파일을 켜지 않으므로 무영향.
 * 실제 D3/D4 데이터가 확보되면 `db/seed/mobile_plan.csv`·`plan_benefit.csv`(prod 경로)로 적재되고
 * 이 더미는 더 이상 필요 없다. CatalogSeedLoader(@Order 1) 다음에 돈다.
 */
@Component
@Profile("dev")
@Order(2)
public class DevSeedLoader implements ApplicationRunner {
    private final CatalogSeedLoader loader;

    public DevSeedLoader(CatalogSeedLoader loader) {
        this.loader = loader;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException, IOException {
        var mobilePlans = new ClassPathResource("db/seed/dev/mobile_plan.csv");
        var planBenefits = new ClassPathResource("db/seed/dev/plan_benefit.csv");
        if (mobilePlans.exists()) {
            loader.loadMobilePlans(mobilePlans);
        }
        if (planBenefits.exists()) {
            loader.loadPlanBenefits(planBenefits);
        }
    }
}
