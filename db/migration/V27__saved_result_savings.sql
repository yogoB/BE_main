-- 랜딩의 "이용자들이 진단에서 확인한 절감액" 표본(D-53). 저장 시점에 BE 가 계산해 둔다.
-- 스냅숏의 CostResult.monthlySavings 는 **정가 대비**라 알뜰폰은 대부분 0 이다 — 사용자가 실제로 본 숫자는
-- "지금 쓰는 요금제 대비"다. currentPlanId 를 같이 저장했을 때만 채워지고, 없으면 NULL(표본에서 제외).
ALTER TABLE saved_result ADD COLUMN monthly_savings_vs_current BIGINT;
