package com.palsaekjo.yogobi.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.subscription.port.MockMydataProvider;
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

/** 결제내역 업로드(Mock 마이데이터) → payment_record 적재 + 가맹점 정규화(G-10). 미매칭은 null·묻는 목록. 취소 제외. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class PaymentImportServiceTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    // 승인 2건(넷플릭스·배달의민족) + 취소 1건. 하드닝된 파서 요구: currency_code=KRW, approved_dtime 14자리.
    static final String MOCK = """
            {"rsp_code":"00000","approved_cnt":3,"approved_list":[
              {"approved_dtime":"20260901093000","approved_amt":13500,"currency_code":"KRW","merchant_name":"NETFLIX.COM","status":"01","paid_type":"01"},
              {"approved_dtime":"20260903120000","approved_amt":9900,"currency_code":"KRW","merchant_name":"배달의민족","status":"01","paid_type":"01"},
              {"approved_dtime":"20260905080000","approved_amt":5000,"currency_code":"KRW","merchant_name":"취소건","status":"02","paid_type":"01"}
            ]}""";

    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    PaymentImportService service;
    final MockMydataProvider provider = new MockMydataProvider(new ObjectMapper());

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE app_user CASCADE");
        service = new PaymentImportService(jdbc);
    }

    @Test void importsPaymentsNormalizesMerchantsAndFlagsUnknown() {
        long userId = jdbc.queryForObject(
                "INSERT INTO app_user(email,password_hash,email_verified) VALUES ('a@example.com','x',TRUE) RETURNING id", Long.class);
        // merchant_alias 는 CatalogSeedLoader 가 기동 시 시드(넷플릭스=1). 확인.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_alias", Integer.class)).isPositive();

        var result = service.importPayments(userId, provider, MOCK);

        assertThat(result.imported()).isEqualTo(2);            // 취소(02) 제외
        assertThat(result.recognized()).isEqualTo(1);          // 넷플릭스만 인식
        assertThat(result.unrecognized()).containsExactly("배달의민족");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_record WHERE user_id=?", Integer.class, userId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT service_id FROM payment_record WHERE merchant_raw='NETFLIX.COM'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT service_id FROM payment_record WHERE merchant_raw='배달의민족'", Long.class)).isNull();
    }

    @Test void reimportingSameFileAddsNothing() {
        long userId = jdbc.queryForObject(
                "INSERT INTO app_user(email,password_hash,email_verified) VALUES ('b@example.com','x',TRUE) RETURNING id", Long.class);
        service.importPayments(userId, provider, MOCK);

        var again = service.importPayments(userId, provider, MOCK);   // 같은 파일 재업로드

        assertThat(again.imported()).isZero();
        assertThat(again.recognized()).isZero();
        assertThat(again.unrecognized()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_record WHERE user_id=?", Integer.class, userId)).isEqualTo(2);
    }

    @Test void malformedPayloadIsRejected() {
        assertThatThrownBy(() -> service.importPayments(1, provider, "not json"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.importPayments(1, provider, "{\"approved_list\":\"x\"}"))
                .isInstanceOf(ApiException.class);
    }
}
