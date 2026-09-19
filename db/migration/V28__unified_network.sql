-- KT 통합요금제(D-58). KT 는 현재 라인업(초이스·베이직·요고)을 "5G/LTE 구분없이" 판다
-- (https://product.kt.com/wDic/index.do?CateCode=6002 배너: "5G/LTE 구분없이 데이터 안심,
--  연령별 맞춤 혜택은 자동 제공", 2026-09-20 확인). 그 요금제를 5G 로만 적어 두면 LTE 를 고른
-- 사용자에게 KT 후보가 **0건**이 된다 — 쓸 수 있는 선택지를 우리가 숨기고 있었다.
ALTER TABLE mobile_plan DROP CONSTRAINT IF EXISTS mobile_plan_network_type_check;
ALTER TABLE mobile_plan ADD CONSTRAINT mobile_plan_network_type_check
    CHECK (network_type IN ('FIVE_G', 'LTE', 'THREE_G', 'LTE_5G'));
