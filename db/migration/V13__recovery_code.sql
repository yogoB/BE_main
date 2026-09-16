-- 비밀번호 복구 코드(D-22). 메일을 쓰지 않으므로(D-21) 이것이 유일한 자기복구 수단이다.
-- 평문은 저장하지 않는다 — 가입/재설정 응답에 한 번만 보여주고 서버는 해시만 갖는다.
-- Google 전용 회원은 비밀번호가 없어 복구할 것도 없으므로 NULL 이다.
ALTER TABLE app_user ADD COLUMN recovery_code_hash VARCHAR(100);
