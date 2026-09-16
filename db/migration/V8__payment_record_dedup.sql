-- 재업로드 중복 방지(PR #2 리뷰). 같은 회원·가맹점·금액·결제일·출처는 같은 결제로 본다.
-- 업로드 항목에는 승인번호가 없어(PaymentHistoryProvider.ImportedPayment) 이 5개가 실질 자연키다.
-- 구독 결제는 월 1회라 같은 날 같은 금액의 별개 결제는 사실상 없다. 있다면 중복으로 흡수된다(알려진 한계).
DELETE FROM payment_record p
      USING payment_record q
      WHERE p.user_id = q.user_id
        AND p.merchant_raw = q.merchant_raw
        AND p.amount = q.amount
        AND p.paid_at = q.paid_at
        AND p.source = q.source
        AND p.id > q.id;

CREATE UNIQUE INDEX uq_payment_record_natural
    ON payment_record (user_id, merchant_raw, amount, paid_at, source);
