-- 메일 없이 가입할 때 받는 프로필(D-20). 이름·닉네임은 화면 표시용이며 인증에는 쓰지 않는다.
-- 기존 회원(메일 토큰으로 가입)에는 값이 없으므로 NULL 을 허용한다.
ALTER TABLE app_user ADD COLUMN name VARCHAR(50);
ALTER TABLE app_user ADD COLUMN nickname VARCHAR(30);

-- 닉네임은 화면에서 사람을 구분하는 이름이라 중복을 막는다. 대소문자·앞뒤 공백을 무시하고 비교한다.
-- 부분 인덱스: 값이 없는 기존 회원은 제약을 받지 않는다.
CREATE UNIQUE INDEX uq_app_user_nickname ON app_user (lower(btrim(nickname))) WHERE nickname IS NOT NULL;
