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
