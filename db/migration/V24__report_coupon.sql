-- 제보 리워드 쿠폰. 제보 1건 = 쿠폰 1장이라 별도 표를 만들지 않고 service_report 행에 붙인다.
-- 쿠폰 코드를 따로 두지 않는다 — 제보 id(UUID)가 곧 쿠폰 코드다.
--
-- 이 쿠폰은 **오늘 아무것도 해제하지 않는다.** 요금 분석은 지금 무료다(수익 모델은 D-01 로 범위 밖).
-- 리워드 경험과 향후 BM 훅으로 먼저 깔아 두는 것이다. 그래서 사용 처리는 coupon_used_at 한 칸뿐이고
-- 사용 API 도 없다 — 쓸 곳이 생기는 날 그때 만든다.
ALTER TABLE service_report
    -- 비로그인 제보는 NULL 이다. 쿠폰을 받으려면 코드를 직접 보관해야 한다.
    -- 탈퇴(삭제권)하면 제보 본문은 남기고 귀속만 끊는다 — 본문은 우리 버그 기록이지 회원의 개인정보가
    -- 아니다(설명란에 개인정보를 적지 말라고 받는다). 다른 표의 CASCADE 와 다른 이유가 이것이다.
    ADD COLUMN user_id BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
    ADD COLUMN coupon_used_at TIMESTAMPTZ;

CREATE INDEX idx_service_report_user ON service_report(user_id) WHERE user_id IS NOT NULL;
