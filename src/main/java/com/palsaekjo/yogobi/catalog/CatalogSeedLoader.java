package com.palsaekjo.yogobi.catalog;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
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
