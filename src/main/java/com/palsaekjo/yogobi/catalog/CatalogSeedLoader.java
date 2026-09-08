package com.palsaekjo.yogobi.catalog;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class CatalogSeedLoader implements ApplicationRunner {
    private static final List<String> TABLES = List.of(
            "subscription_service", "subscription_tier", "bundle_product");
    private final DataSource dataSource;

    public CatalogSeedLoader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException, IOException {
        load(new ClassPathResource("db/seed/subscription_service.csv"),
                new ClassPathResource("db/seed/subscription_tier.csv"),
                new ClassPathResource("db/seed/bundle_product.csv"));
        // 통신 요금제 CSV(D4, 팀원 수집)는 확보되면 적재한다. 아직 없으면 조용히 건너뛴다.
        var mobilePlans = new ClassPathResource("db/seed/mobile_plan.csv");
        if (mobilePlans.exists()) {
            loadMobilePlans(mobilePlans);
        }
        // 제휴 혜택 CSV(D3, 크롤링)는 요금제 다음에 적재한다(요금제를 참조하므로).
        var planBenefits = new ClassPathResource("db/seed/plan_benefit.csv");
        if (planBenefits.exists()) {
            loadPlanBenefits(planBenefits);
        }
    }

    void load(Resource services, Resource tiers, Resource bundles) throws SQLException, IOException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (String table : TABLES) {
                    execute(connection, "CREATE TEMP TABLE seed_" + table
                            + " (LIKE " + table + ") ON COMMIT DROP");
                }
                execute(connection, "ALTER TABLE seed_bundle_product ADD COLUMN tier_ids TEXT NOT NULL"
                        + " CHECK (btrim(tier_ids) <> '')");
                copy(connection, "subscription_service", services);
                copy(connection, "subscription_tier", tiers);
                copy(connection, "bundle_product", bundles);
                execute(connection, """
                        INSERT INTO subscription_service SELECT * FROM seed_subscription_service
                        ON CONFLICT (id) DO UPDATE SET
                            name = EXCLUDED.name, category = EXCLUDED.category,
                            official_url = EXCLUDED.official_url;
                        INSERT INTO subscription_tier SELECT * FROM seed_subscription_tier
                        ON CONFLICT (id) DO UPDATE SET
                            service_id = EXCLUDED.service_id, name = EXCLUDED.name,
                            price = EXCLUDED.price, concurrent_streams = EXCLUDED.concurrent_streams,
                            quality = EXCLUDED.quality, note = EXCLUDED.note;
                        INSERT INTO bundle_product (id, name, price, provider)
                        SELECT id, name, price, provider FROM seed_bundle_product
                        ON CONFLICT (id) DO UPDATE SET
                            name = EXCLUDED.name, price = EXCLUDED.price, provider = EXCLUDED.provider;
                        DELETE FROM bundle_item WHERE bundle_id IN (SELECT id FROM seed_bundle_product);
                        INSERT INTO bundle_item (bundle_id, tier_id)
                        SELECT id, unnest(string_to_array(tier_ids, ','))::BIGINT FROM seed_bundle_product;
                        """);
                for (String table : TABLES) {
                    execute(connection, "SELECT setval(pg_get_serial_sequence('" + table + "', 'id'), "
                            + "GREATEST((SELECT max(id) FROM " + table + "), "
                            + "(SELECT last_value FROM " + table + "_id_seq)))");
                }
                connection.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * 통신 요금제 시드. CSV 는 통신사를 이름으로, 망을 "5G" 로 담으므로 스테이징(TEXT)에 실은 뒤 변환한다.
     * - 통신사 이름 → carrier 업서트(대형 3사 MNO, 그 외 MVNO) → carrier_id 로 조인
     * - "5G/LTE/3G" → "FIVE_G/LTE/THREE_G"
     * - source_url 없는 행은 병합하지 않는다(건너뜀). 그 외 잘못된 값은 CHECK 위반으로 전체 롤백.
     * - id 컬럼이 없으므로 (carrier_id, name) 충돌 기준으로 갱신한다.
     */
    void loadMobilePlans(Resource mobilePlans) throws SQLException, IOException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        CREATE TEMP TABLE seed_mobile_plan (
                            carrier TEXT, plan_name TEXT, network_type TEXT, base_price TEXT,
                            data_mb TEXT, voice_min TEXT, sms_cnt TEXT,
                            contract_discount_12m TEXT, contract_discount_24m TEXT,
                            age_limit TEXT, source_url TEXT, collected_at TEXT
                        ) ON COMMIT DROP""");
                copy(connection, "mobile_plan", mobilePlans);
                execute(connection, """
                        INSERT INTO carrier (name, carrier_type)
                        SELECT DISTINCT btrim(carrier),
                            CASE WHEN btrim(carrier) IN ('SKT', 'KT', 'LGU+', 'LG U+') THEN 'MNO' ELSE 'MVNO' END
                        FROM seed_mobile_plan WHERE btrim(source_url) <> ''
                        ON CONFLICT (name) DO NOTHING;

                        INSERT INTO mobile_plan (carrier_id, name, network_type, base_price, data_mb,
                            voice_min, sms_cnt, contract_discount_12m, contract_discount_24m,
                            age_limit, source_url, collected_at)
                        SELECT c.id, btrim(s.plan_name),
                            CASE btrim(s.network_type)
                                WHEN '5G' THEN 'FIVE_G' WHEN '4G' THEN 'LTE' WHEN '3G' THEN 'THREE_G'
                                ELSE btrim(s.network_type) END,
                            s.base_price::BIGINT, s.data_mb::BIGINT, s.voice_min::BIGINT, s.sms_cnt::BIGINT,
                            nullif(btrim(s.contract_discount_12m), '')::BIGINT,
                            nullif(btrim(s.contract_discount_24m), '')::BIGINT,
                            nullif(btrim(s.age_limit), ''), btrim(s.source_url), s.collected_at::DATE
                        FROM seed_mobile_plan s JOIN carrier c ON c.name = btrim(s.carrier)
                        WHERE btrim(s.source_url) <> ''
                        ON CONFLICT (carrier_id, name) DO UPDATE SET
                            network_type = EXCLUDED.network_type, base_price = EXCLUDED.base_price,
                            data_mb = EXCLUDED.data_mb, voice_min = EXCLUDED.voice_min, sms_cnt = EXCLUDED.sms_cnt,
                            contract_discount_12m = EXCLUDED.contract_discount_12m,
                            contract_discount_24m = EXCLUDED.contract_discount_24m,
                            age_limit = EXCLUDED.age_limit, source_url = EXCLUDED.source_url,
                            collected_at = EXCLUDED.collected_at;
                        """);
                connection.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * 제휴 혜택 시드(D3 크롤링). 크롤러는 내부 ID를 모르므로 CSV 는 요금제를 자연키(통신사+요금제명)로,
     * 서비스·티어는 고정 시드 ID로 참조한다.
     * - 요금제는 (carrier, plan_name) → mobile_plan_id 로 해석한다. 매칭 안 되는 행이 있으면 전체 실패
     *   (조용히 빠뜨리면 혜택 누락 = 잘못된 추천). service_id/tier_id 는 DB FK 가 검증한다.
     * - plan_benefit 에는 자연 unique 키가 없으므로 재실행 멱등성을 위해 CSV 가 다루는 요금제의 혜택을
     *   먼저 지우고 다시 넣는다(bundle_item 과 같은 교체 방식). CSV 에 없는 요금제의 혜택은 건드리지 않는다.
     * - source_url 없는 행은 병합하지 않는다. benefit_type·discount_value·exclusive 정합성은 DB CHECK 가 막는다.
     */
    void loadPlanBenefits(Resource planBenefits) throws SQLException, IOException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        CREATE TEMP TABLE seed_plan_benefit (
                            carrier TEXT, plan_name TEXT, service_id TEXT, tier_id TEXT,
                            benefit_type TEXT, discount_value TEXT, is_exclusive TEXT, exclusive_group TEXT,
                            valid_from TEXT, valid_to TEXT, source_url TEXT, collected_at TEXT
                        ) ON COMMIT DROP""");
                copy(connection, "plan_benefit", planBenefits);
                int unmatched = queryCount(connection, """
                        SELECT count(*) FROM seed_plan_benefit s
                        WHERE btrim(s.source_url) <> '' AND NOT EXISTS (
                            SELECT 1 FROM mobile_plan m JOIN carrier c ON c.id = m.carrier_id
                            WHERE c.name = btrim(s.carrier) AND m.name = btrim(s.plan_name))""");
                if (unmatched > 0) {
                    throw new IllegalStateException(unmatched + "건의 혜택이 존재하지 않는 요금제"
                            + "(통신사+요금제명)를 참조합니다. mobile_plan 시드와 이름을 맞추세요.");
                }
                execute(connection, """
                        DELETE FROM plan_benefit WHERE mobile_plan_id IN (
                            SELECT m.id FROM seed_plan_benefit s
                            JOIN carrier c ON c.name = btrim(s.carrier)
                            JOIN mobile_plan m ON m.carrier_id = c.id AND m.name = btrim(s.plan_name)
                            WHERE btrim(s.source_url) <> '');

                        INSERT INTO plan_benefit (mobile_plan_id, service_id, tier_id, benefit_type,
                            discount_value, is_exclusive, exclusive_group, valid_from, valid_to,
                            source_url, collected_at)
                        SELECT m.id, s.service_id::BIGINT, nullif(btrim(s.tier_id), '')::BIGINT,
                            btrim(s.benefit_type), nullif(btrim(s.discount_value), '')::NUMERIC,
                            coalesce(nullif(btrim(lower(s.is_exclusive)), '')::BOOLEAN, false),
                            nullif(btrim(s.exclusive_group), ''),
                            nullif(btrim(s.valid_from), '')::DATE, nullif(btrim(s.valid_to), '')::DATE,
                            btrim(s.source_url), s.collected_at::DATE
                        FROM seed_plan_benefit s
                        JOIN carrier c ON c.name = btrim(s.carrier)
                        JOIN mobile_plan m ON m.carrier_id = c.id AND m.name = btrim(s.plan_name)
                        WHERE btrim(s.source_url) <> '';
                        """);
                connection.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static int queryCount(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void copy(Connection connection, String table, Resource resource)
            throws SQLException, IOException {
        try (var input = resource.getInputStream()) {
            connection.unwrap(PGConnection.class).getCopyAPI().copyIn(
                    "COPY seed_" + table + " FROM STDIN WITH (FORMAT csv, HEADER MATCH, ENCODING 'UTF8')",
                    input);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
