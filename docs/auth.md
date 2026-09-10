# 회원 인증 · 2026-09-10 (V4: 이메일 검증·비밀번호 재설정·세션 관리 2026-09-11)

사용자 승인: 자체 가입·로그인, Google 로그인, 공통 회원 인증, 본인 확인 후 계정 연결의 네 항목.
비회원은 추천·계산기·카탈로그·단일 발화 챗봇을 사용할 수 있다. 회원은 개인 정보를 저장·관리한다.
이번 구현은 인증·현재 회원 조회·이메일 검증 가입·비밀번호 재설정·로그인 세션 조회/폐기까지이며,
구독·통신비 CRUD는 이 인증 컨텍스트 위에 후속 연결한다.

## API

모든 JSON은 기존 `{data,warnings}` / `{error:{code,message,field}}` 규약을 따른다.
JWT·비밀번호·Google 토큰은 응답 JSON이나 URL에 넣지 않는다.

| 요청 | 입력 | 성공 |
|---|---|---|
| `GET /api/v1/auth/csrf` | 없음 | `{headerName:"X-CSRF-TOKEN",token:"..."}` |
| `POST /api/v1/auth/email/verification` | `{email}`, CSRF | `{message}`, 가입 본인 확인 메일 발송(존재/미존재 동일 응답) |
| `POST /api/v1/auth/signup` | `{token,password}`, CSRF | 현재 회원 + 인증 쿠키. token은 위 메일의 10분·단일 사용 값 |
| `POST /api/v1/auth/login` | `{email,password}`, CSRF | 현재 회원 + 인증 쿠키 |
| `POST /api/v1/auth/password/reset-request` | `{email}`, CSRF | `{message}`, 재설정 메일 발송(자체 계정에만 실제 토큰) |
| `POST /api/v1/auth/password/reset` | `{token,password}` | `{passwordReset:true}`, 비밀번호 변경·모든 세션 폐기·쿠키 삭제 |
| `GET /oauth2/authorization/google` | 브라우저 이동 | Google 인증 화면으로 이동 |
| `GET /login/oauth2/code/google` | Google이 발급한 code/state | 고정 `AUTH_RETURN_URL#auth=success` 또는 `failed` / `account-conflict` |
| `GET /api/v1/me` | 인증 쿠키 | 현재 회원 |
| `GET /api/v1/me/sessions` | 인증 쿠키 | 로그인 세션 목록(`current` 표시, 15분 만료·5분 유휴 제외) |
| `DELETE /api/v1/me/sessions/{id}` | 인증 쿠키·CSRF | `{revoked:true}`, 해당 세션 폐기. 현재 세션이면 쿠키 삭제 |
| `POST /api/v1/auth/logout` | 인증 쿠키·CSRF | `{loggedOut:true}`, 현재 토큰 무효화·쿠키 삭제 |
| `POST /api/v1/auth/logout-all` | 인증 쿠키·CSRF | `{loggedOut:true}`, 이 회원의 모든 토큰 무효화 |
| `POST /api/v1/auth/google/link` | `{password}`, 인증 쿠키·CSRF | `{authorizationUrl:"/oauth2/authorization/google"}` |
| `POST /api/v1/auth/password` | `{password}`, 인증 쿠키·CSRF | 위와 동일. Google 전용 회원의 자체 비밀번호 등록 시작 |

현재 회원: `{id,email,localLogin,googleLogin,currentPlanId,emailVerified}`. 요청에서 userId나 역할을 받지 않는다.
`signup/login`도 CSRF가 필요하다. 입력은 표에 적힌 문자열 필드만 허용한다.
이메일은 trim/lowercase 후 저장, 비밀번호는 15자 이상·UTF-8 72바이트 이하이며 BCrypt cost 12로 저장한다.
자체 가입은 `/auth/email/verification`으로 발송한 본인 확인 메일의 10분·단일 사용 토큰으로만 완료된다.
완료된 계정만 `email_verified`로 저장하며 로그인은 검증 완료 계정에 한한다. 이메일만으로 계정을 자동 병합하지 않는다.
비밀번호 재설정은 자체 계정에만 실제 토큰을 발급하되, 미존재·Google 전용 계정에도 동일한 응답으로 계정 존재를 노출하지 않는다.

## 프론트 연결

1. 비회원 추천은 기존대로 호출한다. 로그인할 때 `GET /api/v1/auth/csrf`를 `credentials:'include'`로 호출한다.
2. 응답의 토큰을 `X-CSRF-TOKEN` 헤더에 넣고 signup/login POST를 호출한다. 모든 회원 요청은 `credentials:'include'`.
3. 인증 정보는 HttpOnly 쿠키로 처리한다. JWT를 JavaScript 변수·localStorage·sessionStorage에 저장하지 않는다.
4. Google 로그인은 BE의 `/oauth2/authorization/google`로 브라우저를 이동시킨다. 콜백 후 고정 프론트 주소로 돌아온다.
5. 가입·로그인·로그아웃·Google 콜백은 기존 HTTP 세션을 없애므로 **그 다음 변경 요청 전에 CSRF를 다시 받는다**.
6. 자체 → Google 연결: 로그인 → 비밀번호 재확인 POST → 응답의 BE authorizationUrl로 이동 → 같은 이메일의 Google 계정 확인.
7. Google → 자체 연결: Google 로그인 → 새 password POST → 같은 Google 계정 재확인. 해시만 임시 세션에 저장하며 평문은 보관하지 않는다.

연결 의도는 5분간 유효하며 시작한 회원·발급 JWT·브라우저에 묶인다. 계정 연결 완료 시 기존 모든 토큰을 폐기하고 새 토큰을 발급한다.
단순 이메일 일치에 의한 자동 연결·기존 데이터 병합은 하지 않는다. 다른 이메일 계정 연결은 지원하지 않는다.

## 운영 설정

- `JWT_SECRET`: `openssl rand -base64 32`로 생성한 독립 비밀 키. AI 내부 토큰과 공유하지 않는다.
  빈 값이면 비회원 API는 정상이고 회원 로그인은 503. 짧거나 잘못된 Base64·AI와 같은 키는 기동 시 거부한다.
- 이메일 검증·비밀번호 재설정 메일: `AUTH_EMAIL_ENABLED=true`, `AUTH_EMAIL_FROM`, SMTP(`SMTP_HOST/PORT/USERNAME/PASSWORD`, STARTTLS)를 설정한다.
  비활성(기본값)이면 자체 가입·재설정 요청은 503이고 Google 로그인만 동작한다. `AUTH_EMAIL_LINK_URL`은 고정 HTTPS(로컬만 loopback)만 허용한다.
- `GOOGLE_AUTH_ENABLED=true`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`를 설정한다.
  Google Cloud의 웹 OAuth 클라이언트에 정확히 같은 콜백 URL을 등록한다. scope는 `openid email`만 사용한다.
- `AUTH_RETURN_URL`: 로그인 후 돌아갈 고정 프론트 URL. 요청의 redirect/returnUrl 값은 사용하지 않는다.
- 운영은 `AUTH_SECURE_COOKIES=true`, `AUTH_SESSION_COOKIE_NAME=__Host-YGB_SESSION`, HTTPS.
  인증 쿠키는 `__Host-YGB_AUTH`, `__Host-YGB_BINDING`, Secure·HttpOnly·SameSite=Lax·Path=/·Domain 없음.
- **HTTP 로컬 개발만** `AUTH_SECURE_COOKIES=false`, `AUTH_SESSION_COOKIE_NAME=YGB_SESSION`.
  인증 쿠키 이름도 `YGB_AUTH`, `YGB_BINDING`으로 바뀐다.
- `YOGOBI_CORS_ALLOWED_ORIGINS`: 정확한 프론트 오리진만 쉼표로 지정. wildcard 금지. 운영 설정에서는 localhost를 제거한다.
  프론트와 BE는 같은 사이트에 두거나 프론트 도메인에서 BE를 프록시한다. 서로 다른 사이트의 쿠키 인증은 이 Lax 구성의 대상이 아니다.
- JWT 만료는 15분, refresh token 없음. 만료 후 재로그인한다. 로그인 세션은 15분 만료·5분 유휴로 정리한다. OAuth·CSRF 임시 세션은 5분.
  단일 앱 인스턴스의 메모리 HTTP 세션을 사용하므로 앱 재시작 시 진행 중 OAuth/CSRF를 다시 시작한다.
- IP는 소켓의 `remoteAddr`만 신뢰한다. 프록시가 모든 사용자를 같은 IP로 보이게 하면 인증 요청 제한도 공유된다.
  운영 프록시의 신뢰 범위를 확인하고 안전한 실제 IP 복원 설정을 적용해야 한다. 요청 헤더를 그대로 신뢰해 제한을 우회시키지 않는다.

## 에러

| 코드 | HTTP | 의미 |
|---|---|---|
| `YGB-REQ-001` | 400 | 입력 형식 오류 |
| `YGB-AUTH-001` | 401 | 로그인 필요·잘못된 자격 증명 |
| `YGB-AUTH-403` | 403 | CSRF·권한 검증 실패 |
| `YGB-AUTH-404` | 404 | 로그인 세션을 찾을 수 없음 |
| `YGB-AUTH-409` | 409 | 가입·연결 충돌, 기존 계정 확인 필요 |
| `YGB-AUTH-429` | 429 | 15분 제한: IP 40회, 이메일 로그인 10회, 재확인 10회, 메일 발송 3회 |
| `YGB-AUTH-503` | 503 | 회원/Google 인증 미설정 |
| `YGB-AUTH-LINK` | 400 | 본인 확인 링크 만료·사용 불가 (재요청 필요) |
| `YGB-AUTH-MAIL` | 503 | 본인 확인 메일 발송 불가 (SMTP 미설정·전송 실패) |

CSRF 검사가 인증 검사보다 먼저 거부하는 회원 변경 요청은 비로그인 상태에서도 403일 수 있다.
Google 콜백의 오류는 상세 공급자 응답을 노출하지 않고 고정 프론트 상태로 안내한다.

## 검증과 한계

`AuthSecurityTest`는 PostgreSQL·실제 Spring 필터·JWT 서명 검증을 사용한다. Google만 로컬 HTTP OIDC 공급자로 대체한다.
정상 가입/연결부터 위조·재사용·다른 회원 접근 시도를 검사한다. 자세한 결과는 `docs/auth-security.md`.
실제 Google Cloud 설정·실사용자 Google 계정·운영 HTTPS·브라우저 쿠키 정책은 별도 운영 환경 확인이 필요하다.

참고: [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect),
[Spring OAuth2 Login](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/login/core.html),
[Spring CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html).
