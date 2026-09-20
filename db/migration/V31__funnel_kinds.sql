-- 퍼널 4·5단계가 2026-09-18 부터 한 건도 안 쌓이고 있었다. funnel_daily.kind 의 CHECK 가 D-36 당시의
-- 세 종류만 허용하는데, D-52 에서 CALENDAR_SHOWN·RESULT_SAVED 를 부르기 시작했다. FunnelCounter 는
-- funnel_daily 를 먼저 쓰고 실패를 삼키므로(지표 때문에 기능이 멈추면 안 된다) 두 번째 쓰기인
-- funnel_event 까지 통째로 건너뛰었다 — 두 표 모두 비어 있다. 예외는 WARN 한 줄로만 남았고,
-- 화면에서는 "아직 아무도 안 했다"와 구분되지 않았다.
--
-- 종류를 현재 집합으로 넓힌다. CHECK 를 없애지는 않는다 — 오타가 새 단계처럼 보이면 안 된다.
-- INPUT_STARTED·INPUT_COMPLETED 는 화면만 볼 수 있는 단계이고(보고서 §9.2 "결과 도달률"의 분모),
-- 공개 경로가 받는 것은 그 둘뿐이다(FunnelEventController).
ALTER TABLE funnel_daily DROP CONSTRAINT IF EXISTS funnel_daily_kind_check;
ALTER TABLE funnel_daily ADD CONSTRAINT funnel_daily_kind_check
    CHECK (kind IN ('GATE_SHOWN', 'REPORT_SHOWN', 'MEMBER_LOGIN',
                    'CALENDAR_SHOWN', 'RESULT_SAVED',
                    'INPUT_STARTED', 'INPUT_COMPLETED'));

COMMENT ON COLUMN funnel_daily.kind IS
    'GATE_SHOWN=비회원이 추천 결과를 받아 게이트를 만남 · REPORT_SHOWN=회원이 리포트를 봄 · MEMBER_LOGIN=Google 로그인 성공 · CALENDAR_SHOWN=변경 시점 판정을 받음 · RESULT_SAVED=결과를 저장 · INPUT_STARTED/INPUT_COMPLETED=화면이 알려 주는 입력 단계';
