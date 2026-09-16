package com.palsaekjo.yogobi.subscription;

import com.palsaekjo.yogobi.common.MatchType;
import com.palsaekjo.yogobi.subscription.domain.MerchantAlias;
import com.palsaekjo.yogobi.subscription.port.PaymentHistoryProvider;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업로드된 결제 내역을 payment_record 로 적재하고 가맹점을 서비스로 정규화한다(G-10).
 * 매칭 실패는 service_id=null 로 두고 사용자에게 물을 목록(unrecognized)으로 돌려준다 — 추측 매핑 금지.
 * data.md §7: 탐지는 정리된 user_subscription 을 보므로, import 는 원천(payment_record)만 채운다(자동 구독 생성 안 함).
 * 재업로드는 중복을 만들지 않는다 — (회원·가맹점·금액·결제일·출처)가 같으면 같은 결제로 보고 건너뛴다(V8 유니크).
 */
@Service
public class PaymentImportService {
    private final JdbcTemplate jdbc;
    private final MerchantNormalizer normalizer = new MerchantNormalizer();

    public PaymentImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Result(int imported, int recognized, List<String> unrecognized) {
    }

    @Transactional
    public Result importPayments(long userId, PaymentHistoryProvider provider, String content) {
        var payments = provider.parse(content);
        List<MerchantAlias> aliases = jdbc.query("SELECT service_id, pattern, match_type FROM merchant_alias",
                (rs, i) -> new MerchantAlias(rs.getLong(1), rs.getString(2), MatchType.valueOf(rs.getString(3))));

        int imported = 0;
        int recognized = 0;
        var unrecognized = new ArrayList<String>();
        for (var p : payments) {
            Long serviceId = normalizer.resolve(p.merchantRaw(), aliases).orElse(null);
            // 이미 있는 결제(같은 자연키, V8)는 조용히 건너뛴다 — 응답의 세 값은 "이번에 새로 저장된 것"만 센다.
            int inserted = jdbc.update("""
                    INSERT INTO payment_record (user_id, merchant_raw, service_id, amount, paid_at, source)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (user_id, merchant_raw, amount, paid_at, source) DO NOTHING
                    """, userId, p.merchantRaw(), serviceId, p.amount(), Date.valueOf(p.paidAt()), provider.source());
            if (inserted == 0) {
                continue;
            }
            imported++;
            if (serviceId != null) {
                recognized++;
            } else {
                unrecognized.add(p.merchantRaw());
            }
        }
        return new Result(imported, recognized, unrecognized);
    }
}
