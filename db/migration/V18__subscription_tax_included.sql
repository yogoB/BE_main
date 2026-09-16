-- 표기 가격에 세금이 포함돼 있는지. 사용자 확인 2026-09-16: 클로드처럼 해외 사업자는 표기가가 세금 별도이고
-- 한국 이용자에게는 결제 시 부가세 10%가 붙는다(전자적 용역 공급). $20 짜리는 실제로 $22 가 빠져나간다.
--
-- **가격에 1.1을 곱해 저장하지 않는다** — 그러면 공식 표기가(출처)가 사라진다.
-- 표기가는 그대로 두고 이 칸으로 구분해, 화면의 원화 환산에서만 세금을 더한다(환산은 ESTIMATED, 계산 제외).
-- 국내 표시가는 총액(부가세 포함)이 관행이므로 기본값은 TRUE 다.
ALTER TABLE subscription_tier
    ADD COLUMN tax_included BOOLEAN NOT NULL DEFAULT TRUE;

-- 비원화 표기는 세금 별도로 본다. 원화 등급은 손대지 않는다.
UPDATE subscription_tier SET tax_included = FALSE WHERE currency <> 'KRW';
