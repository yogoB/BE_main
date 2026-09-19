-- 절감액 표본의 기준을 바꾼다(2026-09-20, D-59). 전에는 "저장 버튼을 누른 회원"(saved_result)만 셌다 —
-- 저장은 극소수가 하는 행동이라 표본이 거의 안 쌓였고, 랜딩은 늘 임계값 미달이었다.
-- 이제 기준은 "로그인한 채 결과 화면을 본 회원"이다. 지금 요금제를 알려준 경우에만 기록한다(모름 ≠ 0).
-- 계정당 한 행 — 가장 최근에 본 결과가 그 사람의 표본이다. 여러 번 본다고 여러 번 세지 않는다.
-- 탈퇴하면 회원 행과 함께 사라진다(D-11 분석본 파기).
CREATE TABLE member_savings (
    user_id         BIGINT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    monthly_savings BIGINT NOT NULL,     -- 지금 요금제 대비. 더 내는 조합이면 음수 그대로 둔다(집계가 거른다)
    seen_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_member_savings_seen ON member_savings(seen_at DESC);
