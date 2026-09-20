-- 알뜰폰 기간 한정 특가를 카탈로그가 표현할 수 있게 한다(2026-09-21, 사용자 지시).
--
-- 지금까지 mobile_plan 에는 base_price 와 약정할인 12/24개월뿐이었다. "3·6·7개월 특가 후 정상가"
-- 를 담을 칸이 없어서, 같은 표에 두 가지가 섞여 있었다 — 특가가를 base_price 에 넣은 것
-- (이지모바일 "7개월 특가" 46,200원)과, 정상가를 넣고 특가는 이름에만 남긴 것
-- (큰사람커넥트 "12개월간 10원" 27,500원). 운영 1,706건 중 13건이 그렇다.
--
-- 그 결과 연 절감액(= 월 × 12)과 화면의 6·12개월 토글이 이 요금제들에서 **틀린 값을 보여 주고 있었다.**
-- 사용자가 실제 사례에서 찾았다.
--
-- promo_months : 특가가 유지되는 개월 수. NULL 이면 기간 한정 특가가 아니다.
-- regular_price: 특가가 끝난 뒤의 월 요금. **모르면 NULL 이다** — 지어내지 않는다.
--                이 값이 NULL 인 동안 기간 절감액은 숫자를 내지 않고 "모른다"고 답한다.
--
-- base_price 의 뜻은 바꾸지 않는다: 지금(1개월차) 내는 금액이다. 기존 계산·정렬이 전부 이 값을
-- 쓰므로 의미를 바꾸면 순위가 통째로 흔들린다.
ALTER TABLE mobile_plan
    ADD COLUMN promo_months  INT,
    ADD COLUMN regular_price BIGINT;

-- 정상가만 있고 기간이 없으면 해석할 수 없다. 기간이 0 이하인 특가도 없다.
ALTER TABLE mobile_plan ADD CONSTRAINT mobile_plan_promo_check
    CHECK ((promo_months IS NULL AND regular_price IS NULL)
        OR (promo_months > 0 AND (regular_price IS NULL OR regular_price > 0)));

COMMENT ON COLUMN mobile_plan.promo_months IS
    '기간 한정 특가가 유지되는 개월 수. NULL=특가 아님. 출처는 요금제 이름과 같은 공식 페이지다';
COMMENT ON COLUMN mobile_plan.regular_price IS
    '특가 종료 후 월 요금. NULL=확인하지 못함(기간 절감액을 내지 않는다). 추정값을 넣지 않는다';
