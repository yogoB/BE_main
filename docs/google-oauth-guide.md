# Google OAuth 2.0 연결 가이드

최종 확인: 2026-09-13 · 대상: Google Cloud 설정자, BE/프론트엔드 개발자

요고비의 Google 로그인 코드는 이미 구현되어 있다. 아래 순서대로 **웹 애플리케이션 OAuth 클라이언트 생성 → BE `.env` 설정 → 내장 계정 화면 확인**만 하면 로컬 로그인을 붙일 수 있다.

> 현재 `yogob-508300` 프로젝트의 `496468...` 클라이언트는 **데스크톱 앱** 유형이다. 요고비 BE 로그인에는 사용할 수 없으므로 삭제하거나 바꾸지 말고, 아래 절차로 **웹 애플리케이션** 클라이언트를 새로 만든다.

## 1. 먼저 기억할 주소 3개

| 용도 | 로컬 주소 | 설정 위치 |
|---|---|---|
| 로그인 시작 | `http://localhost:8080/oauth2/authorization/google` | 브라우저/프론트 |
| Google 콜백 | `http://localhost:8080/login/oauth2/code/google` | Google Console + `GOOGLE_REDIRECT_URI` |
| 로그인 후 화면 | `http://localhost:5173/` (프론트 dev 서버) | `AUTH_RETURN_URL` |

Google Console에는 두 번째 **Google 콜백** 주소를 등록한다. 로그인 후 돌아갈 화면은 콜백이 아니다. 경로 앞에 `/api/v1`을 붙이거나 마지막 `/`, query, fragment를 추가하지 않는다. `localhost`와 `127.0.0.1`도 서로 다른 호스트이므로 섞지 않는다.

이 로그인은 OAuth 2.0 Authorization Code 흐름과 OpenID Connect를 사용한다. Spring Security가 state, nonce, PKCE, code 교환, ID token 서명·발급자·대상을 검사하며, 회원은 Google의 변경되지 않는 `sub` 값으로 식별한다.

## 2. Google Cloud 설정

### 2-1. 앱 기본 설정

1. [yogob 프로젝트의 Google 인증 플랫폼](https://console.cloud.google.com/auth/overview?project=yogob-508300)을 연다.
2. **브랜딩**에서 앱 이름, 사용자 지원 이메일, 개발자 연락 이메일을 입력한다.
3. **대상**에서 일반 Google 계정도 사용할 서비스면 `External`을 선택한다. 개발 상태에서 Console이 테스트 사용자를 요구하면 로그인할 팀 계정을 추가한다.
4. **데이터 액세스**에는 로그인에 필요한 `openid`와 이메일 범위만 둔다. Drive, Gmail 등은 추가하지 않는다.

운영 공개 전에는 실제 홈페이지와 개인정보처리방침 URL, 도메인 소유권, 게시 상태를 Console 안내에 맞춰 완료한다. 추가 민감 범위를 요청하면 Google 검증 조건이 달라질 수 있다. 현재 BE 코드는 `openid email`만 요청한다.

### 2-2. 웹 클라이언트 생성

1. [클라이언트 목록](https://console.cloud.google.com/auth/clients?project=yogob-508300)에서 **클라이언트 만들기**를 누른다.
2. 애플리케이션 유형은 **웹 애플리케이션**을 선택한다.
3. 이름은 `yogobi-local-web`처럼 환경이 드러나게 정한다.
4. **승인된 리디렉션 URI**에 아래 한 줄을 추가한다.

   ```text
   http://localhost:8080/login/oauth2/code/google
   ```

5. 생성 후 같은 웹 클라이언트의 Client ID와 Client secret을 안전한 비밀 저장소에 보관한다.

이 서버 로그인 방식에는 **승인된 JavaScript 원본**을 등록할 필요가 없다. 다운로드한 JSON을 사용할 경우 최상위 키가 `web`인지 확인한다. `installed`이면 데스크톱 클라이언트이므로 사용하지 않는다. JSON과 secret은 Git, 문서, 프론트 코드, 팀 채팅에 올리지 않는다.

Google 공식 설명은 [OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect)와 [웹 서버 OAuth](https://developers.google.com/identity/protocols/oauth2/web-server)를 참고한다.

## 3. 로컬 BE 설정

Java 21과 Docker가 필요하다. 저장소 루트에서 `.env`가 없다면 한 번만 만든다.

```bash
java -version
docker ps
test -f .env || cp .env.example .env
git check-ignore .env
```

마지막 명령이 `.env`를 출력해야 한다. 기존 `.env`가 있으면 DB 주소·포트·비밀번호를 덮어쓰지 않는다. 이 파일은 `KEY=value` 형식이며 `export`나 값 주위 따옴표를 붙이지 않는다.

JWT 서명 키를 로컬에서 만든다.

```bash
openssl rand -base64 32
```

출력값을 `.env`의 `JWT_SECRET`에만 넣고 채팅이나 문서에 복사하지 않는다. Google secret이나 `AI_INTERNAL_TOKEN`을 JWT 키로 재사용하지 않는다.

`.env`에서 다음 항목을 설정한다.

```dotenv
JWT_SECRET=방금_생성한_독립_Base64_키

GOOGLE_AUTH_ENABLED=true
GOOGLE_CLIENT_ID=새_웹_클라이언트_ID
GOOGLE_CLIENT_SECRET=새_웹_클라이언트_SECRET
GOOGLE_REDIRECT_URI=http://localhost:8080/login/oauth2/code/google

AUTH_SECURE_COOKIES=false
AUTH_SESSION_COOKIE_NAME=YGB_SESSION
AUTH_RETURN_URL=http://localhost:5173/
YOGOBI_CORS_ALLOWED_ORIGINS=http://localhost:8080

```

세 비밀 값의 역할은 서로 다르다.

| 값 | 역할 | 노출 범위 |
|---|---|---|
| `GOOGLE_CLIENT_ID` | Google 웹 클라이언트 식별 | BE 설정 |
| `GOOGLE_CLIENT_SECRET` | BE의 code 교환 인증 | BE 비밀 저장소만 |
| `JWT_SECRET` | 요고비 로그인 쿠키 서명 | BE 비밀 저장소만 |

설정이 바뀌면 BE를 재시작한다. 정적 점검은 다음 명령으로 한다.

```bash
python3 scripts/check_auth_config.py --local
```

Google, JWT, URL, 쿠키 관련 오류는 실행 전에 해결한다.

## 4. 실행하고 로그인 확인

```bash
docker compose up -d --wait
./gradlew bootRun
```

기본 포트는 8080이며 Flyway V1~V6가 자동 적용된다. 포트 5432가 이미 사용 중이면 기존 `.env`의 `POSTGRES_PORT` 값을 현재 Compose 포트와 맞춘다. DB 볼륨을 삭제할 필요는 없다.

1. 브라우저에서 프론트 dev 서버(`http://localhost:5173/`)를 연다.
2. **Google로 계속하기**를 누른다.
3. Google 계정을 선택하고 계속한다.
4. 다시 프론트로 돌아와 로그인 완료 안내와 이메일이 보이는지 확인한다.
5. 같은 브라우저에서 `http://localhost:8080/api/v1/me`를 열어 HTTP 200과 아래 핵심 값을 확인한다.

   ```json
   {
     "data": {
       "googleLogin": true,
       "emailVerified": true
     }
   }
   ```

6. 로그아웃한 뒤 같은 Google 계정으로 다시 로그인해 회원 `id`가 유지되는지 확인한다.
7. 시크릿 창에서 로그인 없이 `/api/v1/me`를 열면 401이어야 한다.

로그인 도중 BE를 재시작하거나 5분 이상 지연하거나, 다른 브라우저·호스트로 옮기면 임시 OAuth 세션이 만료될 수 있다. 실패하면 로그인 시작 주소부터 다시 진행한다.

로컬 HTTP에서는 `YGB_AUTH`, `YGB_BINDING`, `YGB_SESSION` 쿠키를 사용한다. 실제 값은 복사하지 말고 개발자 도구에서 HttpOnly, Path=/, SameSite=Lax 속성만 확인한다. 운영에서는 앞의 두 쿠키와 세션 쿠키가 `__Host-` 이름과 Secure 속성을 사용한다.

## 5. 별도 프론트에 연결

프론트가 `http://localhost:5173`, BE가 `http://localhost:8080`이라면 BE 설정만 다음처럼 바꾼다. Google Console 콜백은 그대로다.

```dotenv
AUTH_RETURN_URL=http://localhost:5173/account
YOGOBI_CORS_ALLOWED_ORIGINS=http://localhost:5173
```

로그인 버튼은 Google SDK나 Client secret을 사용하지 않고 BE로 브라우저를 이동시킨다.

```html
<a href="http://localhost:8080/oauth2/authorization/google">Google로 계속하기</a>
```

콜백 후 프론트는 URL의 `#auth=success`, `failed`, `account-conflict`를 처리한 뒤 fragment를 지운다. 회원 API는 쿠키를 보내도록 호출한다.

```javascript
const response = await fetch('http://localhost:8080/api/v1/me', {
  credentials: 'include',
});
if (response.status === 401) location.assign('/login');
```

회원 정보 변경 요청은 먼저 `/api/v1/auth/csrf`에서 새 CSRF 토큰을 받고, 같은 쿠키와 `X-CSRF-TOKEN` 헤더로 전송한다. 자세한 API와 자체 계정 연결 흐름은 [인증 계약](auth.md)을 따른다.

같은 이메일의 자체 회원과 Google 회원을 이메일만으로 자동 병합하지 않는다. 기존 방식으로 로그인한 뒤 본인 재확인을 거쳐 Google을 연결해야 한다. `account-conflict`가 나오면 DB에서 계정을 삭제하거나 강제로 합치지 않는다.

## 6. 운영 설정

운영은 개발과 별도의 **웹 애플리케이션 클라이언트**를 권장한다. 아래 `app.example.com`은 실제 HTTPS 도메인으로 모두 교체한다.

```dotenv
GOOGLE_AUTH_ENABLED=true
GOOGLE_CLIENT_ID=운영_웹_클라이언트_ID
GOOGLE_CLIENT_SECRET=운영_웹_클라이언트_SECRET
GOOGLE_REDIRECT_URI=https://app.example.com/login/oauth2/code/google

JWT_SECRET=운영_전용_독립_Base64_키
AUTH_SECURE_COOKIES=true
AUTH_SESSION_COOKIE_NAME=__Host-YGB_SESSION
AUTH_RETURN_URL=https://app.example.com/
YOGOBI_CORS_ALLOWED_ORIGINS=https://app.example.com
```

Google Console의 운영 클라이언트에도 같은 HTTPS 콜백을 정확히 등록한다. 프록시는 다음 경로를 BE로 전달해야 한다.

- `/oauth2/authorization/google`
- `/login/oauth2/code/google`
- `/api/v1/**`
- (BE 내장 화면 `/account.html`·`/account.js` 는 2026-09-20 에 지웠다 — D-34 로 사라진 비밀번호 폼이었다)

처음에는 프론트와 BE를 같은 오리진이나 같은 사이트의 HTTPS 도메인으로 둔다. 서로 무관한 사이트에서는 현재 SameSite=Lax 쿠키가 API 요청에 전송되지 않을 수 있으며 CORS만으로 해결되지 않는다.

OAuth 임시 세션은 BE 메모리에 있고 5분 유효하다. 로그인 중 자동 정지·재시작·다른 인스턴스로 이동하지 않게 한다. 여러 인스턴스가 필요해질 때 공유 세션 저장소나 동일 인스턴스 라우팅을 추가한다.

운영 전에는 [Google OAuth 운영 정책](https://developers.google.com/identity/protocols/oauth2/production-readiness/policy-compliance)에 따라 브랜드·도메인·개인정보처리방침·게시 상태를 확인한다. 운영 비밀은 배포 플랫폼의 비밀 저장소로 주입하고 로그·환경 덤프·프론트 번들에 넣지 않는다.

## 7. 오류 해결

| 증상 | 원인/확인 | 해결 |
|---|---|---|
| `redirect_uri_mismatch` | Console URI와 BE URI 불일치 | scheme·host·port·path·마지막 `/`까지 같게 맞춤 |
| `invalid_client` | 데스크톱 클라이언트 또는 ID/secret 혼용 | 같은 웹 클라이언트의 ID와 secret 사용 |
| Google 화면 전 503 | JWT 또는 Google 설정 미비 | `JWT_SECRET`, `GOOGLE_AUTH_ENABLED`, 웹 자격 증명 확인 후 재시작 |
| `org_internal`/접근 거부 | Audience 또는 조직 정책 | 외부 서비스는 External, 필요한 테스트 사용자·관리자 정책 확인 |
| callback 404 | 프록시가 SPA로 보냄 | `/login/oauth2/code/google`을 BE로 전달 |
| 로그인 누르면 타임아웃·502 (화면 자체가 안 뜸) | **머신 autostop**. 동의 화면에 머무는 동안 BE 트래픽이 0 이라 머신이 멈추고, Fly 프록시는 깨운 머신을 8.3초만 기다리는데 Spring Boot 기동은 12~13초다 | `min_machines_running = 1` (2026-09-17 적용). `fly logs` 에 `gave up after 15 attempts` 가 있으면 이 건이다 |
| `#auth=failed` | 임시 세션 만료·호스트 변경·재시작 | 같은 창에서 처음부터 5분 안에 재시도 |
| `#auth=account-conflict` | 같은 이메일 자체 회원 존재 | 기존 로그인 후 명시적 Google 연결 |
| 성공 후 `/me` 401 | 쿠키 미저장·Secure/호스트/SameSite 문제 | `credentials`, 로컬 Secure=false, 주소·사이트 구성 확인 |
| 변경 API 403 | CSRF 누락/만료 | 같은 브라우저에서 CSRF를 다시 받아 헤더로 전송 |
| 429 | 15분 인증 제한 | 제한 해제 대신 대기하고 프록시가 IP를 한 값으로 합치는지 확인 |
| 자체 가입 400 | `name`·`email`·`password` 중 누락 | 가입은 메일 없이 `{name,email,password,nickname?}` 하나뿐이다([auth.md](auth.md)) |

오류를 공유할 때는 발생 시각, 단계, HTTP 상태, 비밀 값 없는 경로만 남긴다. callback의 `code`/`state`, 쿠키, JWT, Client secret이 든 전체 URL·HAR·설정 덤프는 공유하지 않는다.

## 8. 키 교체와 인수 확인

Client secret이 노출됐으면 새 secret을 만든 뒤 BE 설정을 새 값으로 바꾸고 재시작한다. 새 값으로 로그인이 성공한 다음 이전 secret을 폐기한다. JWT 키 노출은 별도 사건이므로 JWT 키 교체와 기존 로그인 세션 폐기를 함께 수행한다.

> 현재 프로젝트 점검 메모(2026-09-13): 기존 **데스크톱 클라이언트**의 secret이 로컬 자동화 출력에 표시됐다. 계속 쓰는 클라이언트라면 새 secret으로 소비자를 교체하고 이전 secret을 폐기한다. 사용하지 않는다면 클라이언트를 삭제한다. 새로 만들 웹 클라이언트의 secret과는 별개다.

2026-09-12 코드 검증 기준은 Java 123개(인증 50개 포함)와 `bootJar` 통과다. 이는 로컬 RSA OIDC 공급자를 통한 state·nonce·PKCE·서명 검증을 포함하지만 실제 Google 프로젝트의 설정 완료를 뜻하지 않는다.

```bash
./gradlew test bootJar --no-daemon
node scripts/test_account_security.mjs
node scripts/test_demo_security.mjs
python3 scripts/test_auth_config.py
```

팀 인수인계는 아래 항목이 모두 확인되면 완료다.

- [ ] 클라이언트 유형이 웹 애플리케이션이며 환경별 담당자와 비밀 저장 위치를 기록했다.
- [ ] Console 콜백과 `GOOGLE_REDIRECT_URI`가 정확히 같다.
- [ ] 최초 로그인과 재로그인에서 `/me` 200, `googleLogin=true`, 같은 회원 id를 확인했다.
- [ ] 비로그인 `/me`는 401이고 공개 추천·계산·카탈로그·챗봇은 계속 동작한다.
- [ ] 계정 충돌 시 자동 병합 없이 기존 로그인 후 연결된다.
- [ ] 운영 HTTPS에서 HttpOnly·Secure·SameSite, CORS, 프록시 경로, 인스턴스 연속성을 확인했다.
- [ ] 실패한 항목은 통과로 기록하지 않고 환경·시각·오류 상태를 남겼다.

주요 구현 위치: [SecurityConfig.java](../src/main/java/com/palsaekjo/yogobi/user/SecurityConfig.java), [GoogleLogin.java](../src/main/java/com/palsaekjo/yogobi/user/GoogleLogin.java), [application.properties](../src/main/resources/application.properties), [.env.example](../.env.example), [인증 보안 검증](auth-security.md).
