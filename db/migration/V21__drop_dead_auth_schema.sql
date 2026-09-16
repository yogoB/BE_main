-- D-34 로 죽은 인증 스키마를 지운다. 살아 있는 것은 건드리지 않는다.
--
-- 지우는 것
--   app_user.recovery_code_hash : 복구 코드(D-22)는 D-34 로 사라졌다. main 코드에 참조가 하나도 없다.
--   auth_email_token            : 메일 토큰(D-21)이 사라졌다. 운영에서는 SMTP 가 켜진 적이 없어
--                                 이 표에 행이 들어간 적도 없다(AuthEmail 이 enabled 검사를 먼저 했다).
--
-- 남기는 것 — 죽지 않았다
--   app_user.password_hash  : 운영자 백오피스 로그인(D-32)이 계속 쓴다. 회원만 NULL 이다.
--   app_user.email_verified : Google 이 확인해 준 값으로 채운다(D-34). member() 응답에 실린다.
ALTER TABLE app_user DROP COLUMN recovery_code_hash;
DROP TABLE auth_email_token;
