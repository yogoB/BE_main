package com.palsaekjo.yogobi.subscription.port;

import java.time.LocalDate;
import java.util.List;

/**
 * 결제 내역 소스(마이데이터 본인전송 업로드). 업로드 콘텐츠를 파싱만 하고 저장은 {@code PaymentImportService}가 한다.
 * 구현: {@code MockMydataProvider}(데모), 이후 EML 영수증·수기(data.md §4 A→B→E). 마이데이터 판정은 docs/mydata.md.
 */
public interface PaymentHistoryProvider {
    /** payment_record.source 에 남길 출처. */
    String source();

    /** 업로드 콘텐츠 → 결제 항목. 형식 오류는 ApiException(400). */
    List<ImportedPayment> parse(String content);

    record ImportedPayment(String merchantRaw, long amount, LocalDate paidAt) {
    }
}
