package com.palsaekjo.yogobi.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 보유기간이 지난 개인 데이터만 파기하고 최신 데이터는 보존하는지 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class RetentionServiceTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired RetentionService retention;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clear() { jdbc.execute("TRUNCATE app_user CASCADE"); }

    @Test void purgesExpiredPersonalDataAndKeepsRecent() {
        long id = jdbc.queryForObject(
                "INSERT INTO app_user(email,password_hash,email_verified) VALUES ('r@example.com','x',TRUE) RETURNING id",
                Long.class);
        // payment_record: 보유 12개월. 13개월 전은 파기, 1개월 전은 보존.
        jdbc.update("INSERT INTO payment_record(user_id,merchant_raw,amount,paid_at,source) VALUES (?,?,?,(now()-interval '13 months')::date,'T')", id, "OLD", 1000);
        jdbc.update("INSERT INTO payment_record(user_id,merchant_raw,amount,paid_at,source) VALUES (?,?,?,(now()-interval '1 month')::date,'T')", id, "NEW", 1000);
        // detection_result: 보유 6개월. 7개월 전은 파기, 1개월 전은 보존.
        jdbc.update("INSERT INTO detection_result(user_id,rule_code,target_ref,wasted_amount,detected_at) VALUES (?,?,?,?,now()-interval '7 months')", id, "TIER_DUPLICATE", "old", 1000);
        jdbc.update("INSERT INTO detection_result(user_id,rule_code,target_ref,wasted_amount,detected_at) VALUES (?,?,?,?,now()-interval '1 month')", id, "TIER_DUPLICATE", "new", 1000);
        // 만료된 인증 흔적도 파기 대상.
        jdbc.update("INSERT INTO auth_email_token(token_hash,purpose,email,expires_at) VALUES (repeat('a',64),'SIGNUP','r@example.com',now()-interval '1 minute')");

        var deleted = retention.purge();

        assertThat(deleted.get("payment_record")).isEqualTo(1);
        assertThat(deleted.get("detection_result")).isEqualTo(1);
        assertThat(deleted.get("auth_email_token")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT merchant_raw FROM payment_record", String.class)).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT target_ref FROM detection_result", String.class)).isEqualTo("new");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_email_token", Integer.class)).isZero();
    }
}
