-- D-36 CBT 퍼널 집계. "이탈율이 높으면 비회원에게 연다"는 기준은 숫자가 있어야 작동한다.
--
-- 행을 이벤트마다 쌓지 않고 **날짜×종류로 합친다** — CBT 는 며칠짜리 추세만 보면 되고,
-- 이벤트 원본을 남기면 개인정보 보유기간 판단이 따라붙는다. 여기에는 사람을 식별하는 값이 없다.
--
-- Micrometer 는 쓸 수 없다: 인메모리라 재기동에 리셋되고, Fly 머신은 auto_stop 으로 수시로 내려간다.
CREATE TABLE funnel_daily (
    day     DATE NOT NULL DEFAULT CURRENT_DATE,
    kind    TEXT NOT NULL CHECK (kind IN ('GATE_SHOWN', 'REPORT_SHOWN', 'MEMBER_LOGIN')),
    count   BIGINT NOT NULL DEFAULT 0 CHECK (count >= 0),
    PRIMARY KEY (day, kind)
);

COMMENT ON TABLE funnel_daily IS 'D-36 로그인 게이트 퍼널 일별 집계. 개인 식별값 없음';
COMMENT ON COLUMN funnel_daily.kind IS
    'GATE_SHOWN=비회원이 추천 결과를 받아 게이트를 만남 · REPORT_SHOWN=회원이 리포트를 봄 · MEMBER_LOGIN=Google 로그인 성공';
