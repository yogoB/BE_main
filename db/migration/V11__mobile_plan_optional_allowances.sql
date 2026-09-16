-- 통화·문자 제공량은 "미확인"이 있을 수 있다. 공식 표기가 "기본제공"처럼 수량이 없는 요금제가 실제로 존재한다.
-- NOT NULL 이면 모르는 값을 0(=제공 안 함)이나 999999(=무제한)로 지어내야 한다 — 둘 다 사실과 다르다.
-- 두 컬럼은 추천 후보 선별(data_mb·network_type)과 금액 계산에 쓰이지 않으므로 NULL 허용의 영향 범위가 좁다.
ALTER TABLE mobile_plan ALTER COLUMN voice_min DROP NOT NULL;
ALTER TABLE mobile_plan ALTER COLUMN sms_cnt  DROP NOT NULL;
