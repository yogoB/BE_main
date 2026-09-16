# 인증 보안 시나리오 검증 · 2026-09-10 (세션 갱신 2026-09-11 · 메일 흐름 제거 2026-09-16)

범위: 이번 자체/Google 회원 인증, 쿠키·JWT 검증, 계정 연결, CORS/CSRF, 기존 데모의 API 출력.
로컬 재현 검증이며 침투 테스트 인증서나 운영 환경 전체의 무위험 보증이 아니다.

## 재현

```bash
JAVA_HOME=/tmp/yogobi-jdk21/Contents/Home ./gradlew test bootJar --no-daemon
node scripts/test_demo_security.mjs
node scripts/test_account_security.mjs
python3 scripts/test_auth_config.py
python3 scripts/check_auth_config.py
```

임시 JDK 경로는 이 개발 환경용이다. Java 21과 Docker가 필요하다.
기존 로컬 개발 DB를 사용하지 않고 Testcontainers PostgreSQL에서 현재 Flyway V1~V6를 적용한다.
2026-09-12 최신 전체 회귀: Java 123개·bootJar 통과(실패·오류·스킵 0). 인증 50개는 유지하며 법정 보존 예외의 추가 검증은 `docs/privacy.md`를 참고한다.
2026-09-11: 인증 50개를 포함한 Java 112개 테스트·bootJar 통과(실패·오류·스킵 0).
데모 출력 6개, 계정 화면 가입/재설정 토큰·XSS·CSRF 검사, 설정 검사 31개 시나리오도 통과했다.
정적 설정 점검은 현재 로컬 구성에서 15개 미비 항목을 보고한다(예상된 실패, 비밀 값 출력 없음).
Google은 테스트 전용 RSA 키와 HTTP token/JWKS 서버를 사용한다. `oauth2Login()`이나 인증 객체를
성공으로 mocking하지 않고 실제 state·nonce·PKCE·서명 검증 경로를 통과한다.

## 공격과 결과

| 시나리오 | 검사 결과 |
|---|---|
| 비회원의 `/me`, 구독·탐지 경로 접근 | 401. 보호 경로의 기능 구현 여부와 별도로 인증 필요 |
| A 회원 쿠키로 userId=B 요청, 임의 회원 경로 | 현재 A만 반환, 임의 상세 경로는 404. `/me`는 요청 userId를 신뢰하지 않음 |
| JWT만 탈취, 다른 브라우저 확인 쿠키 조합 | 401 |
| Bearer 헤더·URL query로 토큰 전달 | 회원 인증으로 사용하지 않음 |
| JWT 서명 키로 새 토큰 위조 | 유효 서명이어도 DB에 해당 발급 지문이 없어 401 |
| 잘못된 JWT issuer/audience/subject/서명/알고리즘, 만료·exp 없음·미래 nbf | DB 지문을 테스트에서 등록해도 401. 서명·클레임 검증을 따로 입증 |
| DB 세션 만료·중복 인증 쿠키 | 401 |
| 로그아웃 후 복사한 두 쿠키 재사용 | 401. 모든 기기 로그아웃은 해당 회원의 발급 기록을 삭제 |
| 키 교체 | 이전 서명 토큰 401 |
| CSRF 누락·다른 HTTP 세션의 CSRF 재사용 | 403, 회원 상태 변경 없음 |
| 공격자 CORS 오리진·localhost 임의 포트·도메인 접미사 위장 | 거부. wildcard 설정 자체도 거부 |
| HTTP 세션에 인증 객체를 넣어 JWT 우회 | 401. HTTP 세션은 OAuth/CSRF 용도이며 회원 인증 근거가 아님 |
| 비밀번호 반복 추측·이메일 대소문자 변경 | 정규화 이메일 제한에 걸려 429. 실패 트랜잭션도 시도 횟수 유지 |
| X-Forwarded-For 변경으로 IP 제한 우회 | 같은 소켓 IP로 계산, 429 |
| 이메일 중복·잘못된 길이/타입·추가 userId 필드·SQL 문자열 입력 | 거부. 비밀번호는 BCrypt 해시, DB 입력은 바인딩 파라미터 |
| Google 잘못된 서명·발급자·대상 앱·nonce·만료·이메일 미검증 | 실패 리다이렉트, 회원/세션 생성 없음 |
| 가로챈 Google 인증 코드를 다른 브라우저 state와 조합 | state 또는 PKCE 검증 실패 |
| 완료한 Google 코드/콜백 재사용 | 실패, 임시 세션 폐기 |
| 같은 이메일의 새로운 Google sub로 기존 계정 탈취 | 자동 병합 없이 충돌. 기존 로그인 후 연결 필요 |
| 계정 연결의 비밀번호 오류·중간 사용자 변경·토큰 폐기 | 거부. 원래 회원의 로그인 세션·재확인 결과에 연결 의도를 묶음 |
| Google 전용 회원에 다른 sub로 자체 비밀번호 설정 | 거부. 동일 Google sub 재인증 필요 |
| 쿠키·응답 노출 | Secure/HttpOnly/Host-only/SameSite=Lax. JWT는 JSON·URL에 없음. DB에는 SHA-256 지문만 |
| 복구 코드로 세션 폐기·비밀번호 교체 | 복구 시 기존 세션 전부 401, 옛 비밀번호 401·새 비밀번호 200 (G-15h) |
| 미존재 이메일·틀린 복구 코드 | 같은 401. 계정 존재를 노출하지 않음 (G-15d) |
| 로그인 세션 목록·개별/현재 세션 폐기 | 회원 본인 세션만 조회(현재 표시), 폐기 시 해당 쿠키 401 |
| 오래된 로그인 결과 | 이전 credential_version 의 세션 발급 거부, 쿠키 발급 없음 |
| 재설정 후 안내 메일 실패 | 재설정은 유지되고 이전 세션 복구 없음 |
| Google 연결 전 받은 재설정 링크 | 자격 증명 버전 불일치로 400, 연결된 세션 유지 |
| 서로 다른 재설정 링크 동시 제출 | 200 한 건·400 한 건, 버전 증가 한 번, 교착/500 없음 |
| 복사 쿠키의 유휴·절대 만료 | 15분 JWT/쿠키 수명 확인. 4분 유휴 요청은 갱신, 6분 유휴는 401·목록 제외 |
| 다른 회원 세션 UUID 공격·CSRF 없는 회수 | 목록에 타인 세션/지문 없음, 타인 UUID DELETE 404, CSRF 누락 403 |
| Google 콜백 오설정 | 다른 경로·query·fragment·외부 HTTP는 기동 구성에서 거부 |

## 점검 중 수정한 문제

기존 `static/index.html`이 raw JSON·에러·요금제 이름·혜택 설명을 `innerHTML`로 그대로 삽입했다.
악성 API/시드 문자열이 들어오면 회원과 같은 오리진에서 스크립트를 실행할 수 있었다.
공통 HTML 이스케이프를 모든 동적 출력에 적용했다. `test_demo_security.mjs`가 img/onerror,
svg/onload, 속성 따옴표 탈출 문자열을 실제 렌더 함수에 전달해 코드 대신 텍스트로 출력되는지 검사한다.

서로 다른 재설정 링크가 각각 토큰 행을 잠근 뒤 남은 링크를 삭제하면 잠금 순서가 뒤집힐 수 있었다.
`AuthEmail.consume`에서 이메일별 PostgreSQL 트랜잭션 advisory lock을 먼저 획득해 토큰 소비·정리를 직렬화했다.
Google 콜백도 실제 처리 경로 `/login/oauth2/code/google`만 허용하고 query를 거부하도록 보강했다.

## 세 위험의 대안과 적용 결과

1. 두 쿠키 동시 탈취: 절대 15분·유휴 5분 제한, 로그인 목록·개별 회수·전체 로그아웃을 적용했다.
   공격자가 계속 호출하면 유휴 시간은 갱신되지만 절대 만료는 늘어나지 않는다. 복사 자체를 막는 기기 키/패스키는 브라우저 연동이 필요하여 이번 범위에 추가하지 않았다.
2. 이메일 선점·복구 불가: **해결하지 못했다.** 메일 흐름을 제거해(D-21) 소유 확인 수단이 없다.
   자기복구는 가입 시 한 번 주는 복구 코드뿐이며, 코드를 잃으면 되찾을 방법이 없다.
3. 실환경 미검증: 정적 설정 점검을 구현했다. 실키·Google 계정 승인이 없으므로 외부 검증은 미완료다.

## 남는 위험과 검증하지 않은 범위

- **JWT와 브라우저 확인 쿠키를 모두 탈취하면 만료·폐기 전까지 사용할 수 있다.** 정상 200을 테스트로 확인했고,
  로그아웃 후 401을 확인했다. HttpOnly는 브라우저 전체 침해나 실행 중인 XSS의 API 호출을 막는 보증이 아니다.
- 서버 실행 권한·DB 쓰기 권한·비밀번호 자체까지 탈취한 공격은 이 쿠키 보호로 해결되지 않는다.
  실제 키가 유출되면 키 교체와 `auth_session` 폐기, 침해 원인 제거가 필요하다. 비밀번호 자기복구는 복구 코드로만 하며 복구 시 기존 세션을 모두 폐기한다.
- **자체 가입은 이메일 소유를 확인하지 않는다**(D-21 로 메일 흐름 제거). 타인의 주소로 가입할 수 있고
  `email_verified` 는 FALSE 로 남으며 로그인은 그 값을 보지 않는다. 남은 위험이며, 막으려면
  소유 확인 경로를 새로 구현해야 한다 — 켤 수 있는 플래그는 없다.
- 실제 Google 계정 승인, 운영 OAuth 클라이언트·HTTPS·브라우저 쿠키 저장/CORS·프록시 IP 설정은 테스트 공급자로 검증할 수 없다.
  로컬 임의 공급자만 테스트에 사용하며 운영 코드의 Google URL은 Spring의 Google 설정으로 고정한다.
- 신규 `/me/subscriptions`, 결제 업로드, 통신비 변경, 탐지 조회의 객체별 권한은 해당 API 구현 시 별도 검증한다.
  이번 테스트는 `/me` 현재 회원 격리와 보호 경로 진입 차단까지만 입증한다.
- 분산 대량 요청·프록시 뒤 IP 공유, 외부 프론트 전체 XSS, 외부 Google 자체의 보안은 이번 검증 범위 밖이다.

공식 구현 근거: [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect),
[Spring CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html).

추가 구현 근거: [OWASP 비밀번호 복구](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html),
[PostgreSQL 잠금](https://www.postgresql.org/docs/current/explicit-locking.html).
