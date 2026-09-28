package com.palsaekjo.yogobi.subscription;

import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.MatchType;
import com.palsaekjo.yogobi.subscription.domain.MerchantAlias;
import com.palsaekjo.yogobi.subscription.port.PaymentHistoryProvider;
import java.sql.Date;
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

    private static final int MAX_CONTENT_CHARS = 1_000_000;
    private static final int MAX_ITEMS = 1_000;
    private static final int MAX_MERCHANT_CHARS = 200;

    public record Result(int imported, int recognized, List<String> unrecognized) {
    }

    @Transactional
    public Result importPayments(long userId, PaymentHistoryProvider provider, String content) {
        // 업로드에는 끝이 있다(G-76). 상한 없이 한 트랜잭션에서 전부 INSERT 하면 한 요청이 커넥션을 오래 붙잡는다.
        if (content != null && content.length() > MAX_CONTENT_CHARS)
            throw ApiException.requiredMissing("payload", "파일이 너무 커요(1MB까지). 기간을 나눠 올려 주세요.");
        var payments = provider.parse(content);
        if (payments.size() > MAX_ITEMS)
            throw ApiException.requiredMissing("approved_list", "한 번에 " + MAX_ITEMS + "건까지 올릴 수 있어요. 기간을 나눠 올려 주세요.");
        if (payments.stream().anyMatch(p -> p.merchantRaw().length() > MAX_MERCHANT_CHARS))
            throw ApiException.requiredMissing("approved_list", "가맹점 이름이 너무 긴 항목이 있어요. 내려받은 파일 그대로 올려 주세요.");
        List<MerchantAlias> aliases = jdbc.query("SELECT service_id, pattern, match_type FROM merchant_alias",
                (rs, i) -> new MerchantAlias(rs.getLong(1), rs.getString(2), MatchType.valueOf(rs.getString(3))));

        int imported = 0;
        int recognized = 0;
        // 매달 같은 가맹점은 한 번만 묻는다 — 1년 치면 12번 떴다(G-86 k).
        var unrecognized = new java.util.LinkedHashSet<String>();
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
        return new Result(imported, recognized, List.copyOf(unrecognized));
    }
}
