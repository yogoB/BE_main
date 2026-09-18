-- 결과 화면의 "마이페이지에 저장"(2026-09-18, D-51). 스냅숏 금액은 저장 시점에 BE 가 같은 계산기로 만든다 —
-- 화면 숫자를 되돌려 받지 않는다(절대 원칙 2·4). 탈퇴 시 회원 행과 함께 사라진다(D-11 분석본 파기).
CREATE TABLE saved_result (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    request    JSONB NOT NULL,      -- 저장 당시 계산 요청(planId·tierIds·optional). 다시 열 때 그대로 재계산할 수 있다
    cost       JSONB NOT NULL,      -- 저장 당시 CostResult 스냅숏
    saved_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_saved_result_user ON saved_result(user_id, saved_at DESC);
