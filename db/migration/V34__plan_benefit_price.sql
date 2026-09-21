-- 조건부 할인가를 담는다(2026-09-21, 사용자 지시). 기간형(V32)과 **다른 종류**다.
--
--   기간형 promo_months/regular_price : 누구에게나 같고, 달력이 정한다. 우리가 안다.
--   조건형 benefit_price              : 사람마다 다르고, 조건 충족이 정한다. 우리는 모른다.
--
-- KB리브모바일이 이 모양이다. 페이지가 "기본료 월 24,900원 / 최종 혜택가 월 5,900원" 으로 적고
-- SKT망 요금제에는 "최대 할인가(VAT포함)" 라고 쓴다 — **"최대"** 가 조건부라는 뜻이다.
-- 그런데 그 조건이 페이지 어디에도 없다(SPA 라 상세 경로도 없다). 금액은 알고 조건은 모른다.
--
-- benefit_price : 조건을 채웠을 때의 월 요금. base_price 보다 **작아야** 한다.
-- benefit_label : 출처가 그 금액을 부르는 이름을 **그대로** 적는다("최종 혜택가"·"최대 할인가").
--                 우리가 조건을 지어내지 않기 위한 칸이다 — 아는 만큼만 적는다.
--
-- **순위에는 쓰지 않는다.** 조건을 채웠는지 우리가 모르는 값으로 1순위를 정하면, 조건을 못 채운
-- 사용자에게 없는 금액을 약속하는 셈이다(절대 원칙 1 "미사용 혜택은 0원" 과 같은 논리).
-- 지금은 **있다는 사실만 알린다.** 조건을 묻는 화면이 생기면 그때 계산에 넣는다.
ALTER TABLE mobile_plan
    ADD COLUMN benefit_price BIGINT,
    ADD COLUMN benefit_label TEXT;

ALTER TABLE mobile_plan ADD CONSTRAINT mobile_plan_benefit_price_check
    CHECK ((benefit_price IS NULL AND benefit_label IS NULL)
        OR (benefit_price > 0 AND benefit_price < base_price AND btrim(benefit_label) <> ''));

COMMENT ON COLUMN mobile_plan.benefit_price IS
    '조건을 채웠을 때의 월 요금. 순위 계산에 쓰지 않는다 — 조건 충족 여부를 우리가 모른다';
COMMENT ON COLUMN mobile_plan.benefit_label IS
    '출처가 그 금액을 부르는 이름 그대로. 조건을 지어내지 않기 위한 칸이다';
