package com.palsaekjo.yogobi.catalog;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
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
    @Value("${CATALOG_CSV_DIR:}")
    private String catalogDirectory = "";
    @Value("${yogobi.catalog.combined-csv:}")
    private String combinedCsv = "";

    public CatalogSeedLoader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException, IOException {
        // 외부 관리 모드에서는 내장 시드가 운영 CSV나 마지막 정상 DB를 덮어쓰면 안 된다.
        if (!catalogDirectory.isBlank()) return;
        // 합본 CSV가 원본으로 설정돼 있으면 그쪽이 적재한다(D-21). 개별 시드와 이중 적재하지 않는다.
        if (!combinedCsv.isBlank()) return;
        // 내장 시드도 합본 한 파일이 원본이다 — 같은 표를 두 파일로 두면 반드시 어긋난다.
        var parts = CombinedCatalogCsv.bundled();
        load(parts.get("subscription_service"), parts.get("subscription_tier"), parts.get("bundle_product"));
        // 혜택은 요금제를 참조하므로 순서를 지킨다.
        // 합본에 없는 요금제는 물러난다(G-34). 업서트만 하면 이름을 고친 옛 행("케이티엠모바일")과
        // 개발용 더미("5G 웨이브팩" 999999MB)가 영원히 남아 추천 1순위에 올라온다 — 운영에서 실제로 그랬다.
        loadMobilePlans(parts.get("mobile_plan"), true);
        loadMobilePlanPromos(parts.get("mobile_plan_promo"));
        loadPlanBenefits(parts.get("plan_benefit"));
    }

    void load(Resource services, Resource tiers, Resource bundles) throws SQLException, IOException {
        try (var connection = dataSource.getConnection()) {
            boolean ownTransaction = connection.getAutoCommit();
            if (ownTransaction) connection.setAutoCommit(false);
            try {
                for (String table : TABLES) {
                    execute(connection, "CREATE TEMP TABLE seed_" + table
                            + " (LIKE " + table + " INCLUDING DEFAULTS) ON COMMIT DROP");
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
                            official_url = EXCLUDED.official_url, active = TRUE;
                        INSERT INTO subscription_tier SELECT * FROM seed_subscription_tier
                        ON CONFLICT (id) DO UPDATE SET
                            service_id = EXCLUDED.service_id, name = EXCLUDED.name,
                            price = EXCLUDED.price, concurrent_streams = EXCLUDED.concurrent_streams,
                            quality = EXCLUDED.quality, note = EXCLUDED.note,
                            currency = EXCLUDED.currency, tax_included = EXCLUDED.tax_included, active = TRUE;
                        INSERT INTO bundle_product (id, name, price, provider)
                        SELECT id, name, price, provider FROM seed_bundle_product
                        ON CONFLICT (id) DO UPDATE SET
                            name = EXCLUDED.name, price = EXCLUDED.price, provider = EXCLUDED.provider, active = TRUE;
                        DELETE FROM bundle_item WHERE bundle_id IN (SELECT id FROM seed_bundle_product);
                        INSERT INTO bundle_item (bundle_id, tier_id)
                        SELECT id, unnest(string_to_array(tier_ids, ','))::BIGINT FROM seed_bundle_product;
                        -- 합본에서 사라진 행은 물러난다. 업서트만 하면 <b>지워도 운영에 영원히 남는다</b> —
                        -- 요금제는 G-34 로 이미 고쳤는데(loadMobilePlans 의 retireMissing) 구독 쪽은 빠져 있었다.
                        -- 2026-09-20: 연간 총액(174,000)이 월 단가 표에 섞인 등급을 CSV 에서 지웠는데
                        -- 운영 응답에 그대로 살아 있었다. 지우는 것은 삭제가 아니라 active=false 다 —
                        -- 이미 그 등급을 고른 회원의 참조는 남는다.
                        UPDATE subscription_service SET active = FALSE WHERE active AND id NOT IN (SELECT id FROM seed_subscription_service);
                        UPDATE subscription_tier SET active = FALSE WHERE active AND id NOT IN (SELECT id FROM seed_subscription_tier);
                        UPDATE bundle_product SET active = FALSE WHERE active AND id NOT IN (SELECT id FROM seed_bundle_product);
                        -- 가맹점 별칭(data.md §6, G-10). 소량 고정 참조데이터라 서비스 적재 직후 인라인 시드(패턴은 대문자·한글 원문).
                        INSERT INTO merchant_alias (service_id, pattern, match_type) VALUES
                            (1,'NETFLIX','CONTAINS'),(1,'넷플릭스','CONTAINS'),
                            (2,'DISNEY','CONTAINS'),(2,'디즈니','CONTAINS'),
                            (3,'TVING','CONTAINS'),(3,'티빙','CONTAINS'),
                            (4,'WAVVE','CONTAINS'),(4,'콘텐츠웨이브','CONTAINS'),
                            (5,'WATCHA','CONTAINS'),(5,'왓챠','CONTAINS'),
                            (6,'GOOGLE *YOUTUBE','PREFIX')
                        ON CONFLICT (pattern, match_type) DO NOTHING;
                        """);
                for (String table : TABLES) {
                    execute(connection, "SELECT setval(pg_get_serial_sequence('" + table + "', 'id'), "
                            + "GREATEST((SELECT max(id) FROM " + table + "), "
                            + "(SELECT last_value FROM " + table + "_id_seq)))");
                }
                if (ownTransaction) connection.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                if (ownTransaction) connection.rollback();
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
        loadMobilePlans(mobilePlans, false);
    }

    /**
     * {@code retireMissing} 이면 이 파일에 없는 요금제를 {@code active=false} 로 내린다 — 파일이 <b>전체</b>일 때만
     * (합본 시드·외부 스냅샷). 개발 더미처럼 일부만 담은 파일에 켜면 나머지 전부가 물러난다.
     * 지우지 않고 내리는 이유는 회원의 현재 요금제가 그 행을 가리킬 수 있어서다(G-30 은 비활성도 찾는다).
     */
    void loadMobilePlans(Resource mobilePlans, boolean retireMissing) throws SQLException, IOException {
        try (var connection = dataSource.getConnection()) {
            boolean ownTransaction = connection.getAutoCommit();
            if (ownTransaction) connection.setAutoCommit(false);
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
                                -- 통합요금제(D-58): 한 요금제가 5G·LTE 양쪽에서 쓰인다. KT 현재 라인업이 그렇다.
                                WHEN '5G/LTE' THEN 'LTE_5G' WHEN 'LTE/5G' THEN 'LTE_5G'
                                ELSE btrim(s.network_type) END,
                            s.base_price::BIGINT, s.data_mb::BIGINT,
                            nullif(btrim(s.voice_min), '')::BIGINT, nullif(btrim(s.sms_cnt), '')::BIGINT,
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
                            collected_at = EXCLUDED.collected_at, active = TRUE;
                        """);
                if (retireMissing) execute(connection, """
                        UPDATE mobile_plan m SET active = FALSE WHERE active AND NOT EXISTS (
                            SELECT 1 FROM seed_mobile_plan s JOIN carrier c ON c.name = btrim(s.carrier)
                            WHERE m.carrier_id = c.id AND m.name = btrim(s.plan_name) AND btrim(s.source_url) <> '');
                        """);
                if (ownTransaction) connection.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                if (ownTransaction) connection.rollback();
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
            boolean ownTransaction = connection.getAutoCommit();
            if (ownTransaction) connection.setAutoCommit(false);
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
                if (ownTransaction) connection.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                if (ownTransaction) connection.rollback();
                throw e;
            }
        }
    }

    /** 외부 CSV 전체를 단일 트랜잭션으로 반영한다. 기존 회원 참조는 active=false로 보존한다. */
    void loadSnapshot(java.util.Map<String, Resource> files) throws SQLException, IOException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var shared = new CatalogSeedLoader(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            try {
                shared.load(files.get("subscription_service"), files.get("subscription_tier"), files.get("bundle_product"));
                shared.loadMobilePlans(files.get("mobile_plan"), true);
                execute(connection, "DELETE FROM plan_benefit");
                shared.loadPlanBenefits(files.get("plan_benefit"));
                execute(connection, """
                        UPDATE subscription_service SET active = FALSE WHERE id NOT IN (SELECT id FROM seed_subscription_service);
                        UPDATE subscription_tier SET active = FALSE WHERE id NOT IN (SELECT id FROM seed_subscription_tier);
                        UPDATE bundle_product SET active = FALSE WHERE id NOT IN (SELECT id FROM seed_bundle_product);
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

    /**
     * 기간 한정 특가를 요금제에 붙인다(2026-09-21). {@code (carrier, plan_name)} 으로 매칭한다.
     *
     * <p><b>매번 전부 지우고 다시 쓴다.</b> 업서트만 하면 특가가 끝나 합본에서 행을 지워도 DB 에는
     * 영원히 남는다 — G-53 에서 구독 등급이 그랬고, 특가는 <b>끝나는 것이 정상</b>이라 더 위험하다.
     *
     * <p>{@code regular_price} 가 비어 있으면 NULL 로 둔다. 특가 종료 후 금액을 모른다는 뜻이고,
     * 그 상태에서 기간 절감액은 숫자를 내지 않는다. 추정값을 넣으면 그게 제일 위험한 숫자가 된다.
     *
     * <p>매칭 안 되는 행은 조용히 버리지 않고 <b>전체를 실패시킨다</b> — 요금제 이름을 고쳤는데
     * 특가 행만 옛 이름으로 남으면 특가가 통째로 사라지고 아무도 모른다(plan_benefit 과 같은 규칙).
     */
    void loadMobilePlanPromos(Resource promos) throws SQLException, IOException {
        try (var connection = dataSource.getConnection()) {
            boolean ownTransaction = connection.getAutoCommit();
            if (ownTransaction) connection.setAutoCommit(false);
            try {
                execute(connection, """
                        CREATE TEMP TABLE seed_mobile_plan_promo (
                            carrier TEXT, plan_name TEXT, promo_months TEXT, regular_price TEXT,
                            source_url TEXT, collected_at TEXT
                        ) ON COMMIT DROP""");
                copy(connection, "mobile_plan_promo", promos);
                try (var check = connection.createStatement();
                        var rs = check.executeQuery("""
                                SELECT count(*) FROM seed_mobile_plan_promo s
                                WHERE btrim(s.plan_name) <> '' AND NOT EXISTS (
                                    SELECT 1 FROM mobile_plan m JOIN carrier c ON c.id = m.carrier_id
                                    WHERE c.name = btrim(s.carrier) AND m.name = btrim(s.plan_name))""")) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        throw new IllegalArgumentException("mobile_plan_promo 에 없는 요금제(통신사+요금제명) "
                                + rs.getInt(1) + "건을 참조합니다. mobile_plan 시드와 이름을 맞추세요.");
                    }
                }
                execute(connection, """
                        UPDATE mobile_plan SET promo_months = NULL, regular_price = NULL
                         WHERE promo_months IS NOT NULL OR regular_price IS NOT NULL;

                        UPDATE mobile_plan m SET
                            promo_months  = nullif(btrim(s.promo_months), '')::INT,
                            regular_price = nullif(btrim(s.regular_price), '')::BIGINT
                        FROM seed_mobile_plan_promo s JOIN carrier c ON c.name = btrim(s.carrier)
                        WHERE m.carrier_id = c.id AND m.name = btrim(s.plan_name)
                          AND btrim(s.promo_months) <> '';
                        """);
                if (ownTransaction) connection.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                if (ownTransaction) connection.rollback();
                throw e;
            }
        }
    }

    private static String copyColumns(String table) {
        return switch (table) {
            case "subscription_service" -> " (id,name,category,official_url)";
            case "subscription_tier" -> " (id,service_id,name,price,concurrent_streams,quality,note,currency,tax_included)";
            case "bundle_product" -> " (id,name,price,provider,tier_ids)";
            default -> "";
        };
    }

    private static void copy(Connection connection, String table, Resource resource)
            throws SQLException, IOException {
        try (var input = resource.getInputStream()) {
            connection.unwrap(PGConnection.class).getCopyAPI().copyIn(
                    "COPY seed_" + table + copyColumns(table) + " FROM STDIN WITH (FORMAT csv, HEADER MATCH, ENCODING 'UTF8')",
                    input);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
