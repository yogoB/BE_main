-- 해외 결제 구독(ChatGPT·Claude 등)을 카탈로그에 넣되 원화 금액을 지어내지 않는다.
-- 사용자 결정 2026-09-16: 가격은 표기 통화 그대로 저장하고, 원화는 오픈 API 환율로 하루 1회 환산해 **표시만** 한다.
-- 계산(pricing)은 KRW 등급만 쓴다 — 환산값은 ESTIMATED 라 D-17에 따라 계산·추천 순위에서 제외한다.
-- 사용자가 실제 결제액을 넣으면 그 값이 USER_PROVIDED 로 계산에 들어간다(user_subscription.monthly_price).
ALTER TABLE subscription_tier
    ADD COLUMN currency TEXT NOT NULL DEFAULT 'KRW' CHECK (currency IN ('KRW', 'USD'));

-- 환율 스냅샷. 배치가 하루 1회 갱신하고 실패하면 마지막 성공 값이 그대로 남는다.
-- 요청 경로에서는 외부를 호출하지 않는다(D-05 유지) — 화면은 항상 이 표만 읽는다.
CREATE TABLE fx_rate (
    base       TEXT NOT NULL CHECK (base ~ '^[A-Z]{3}$'),
    quote      TEXT NOT NULL CHECK (quote ~ '^[A-Z]{3}$'),
    rate       NUMERIC(18, 6) NOT NULL CHECK (rate > 0),
    rate_date  DATE NOT NULL,
    source_url TEXT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (base, quote)
);

-- 초기값: 배치가 처음 도는 것은 내일 09:15(KST)이므로 그때까지 화면이 비지 않게 한 건을 넣는다.
-- 지어낸 값이 아니라 아래 출처에서 실제로 받은 값이며 기준일을 함께 남긴다.
INSERT INTO fx_rate (base, quote, rate, rate_date, source_url)
VALUES ('USD', 'KRW', 1359.150000, DATE '2026-09-15', 'https://api.frankfurter.dev/v1/latest');
