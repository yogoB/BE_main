-- 로그인 전 선택 동의 중 "절감 추천 알림"은 이벤트·혜택 마케팅과 목적이 달라 별도 증빙으로 남긴다.
ALTER TABLE user_consent DROP CONSTRAINT user_consent_item_check;
ALTER TABLE user_consent ADD CONSTRAINT user_consent_item_check
    CHECK (item IN ('ESSENTIAL', 'SAVINGS_ALERT', 'MARKETING'));
