# 세 기능이 왜 안 되는가 — 추적 결과와 조치 방법

2026-09-16 조사. 배포본 `https://yogob.fly.dev`(프론트) + `yogob-api`(BE) 기준.

## 한 줄 요약

| 기능 | 코드 | 설정 | 실제 응답 | 조치 |
|---|---|---|---|---|
| Google 로그인 | ✅ 완성 | ❌ 없음 | `503 YGB-AUTH-503` | §1 — Cloud 클라이언트 발급 + secret 4개 |
| 가입 메일 발송 | ✅ 완성 | ⚠️ 비밀번호만 없음 | `503 YGB-AUTH-MAIL` | §2 — **명령 한 줄** |
| Google 캘린더 | ❌ **미구현** | — | 버튼이 안내문만 토글 | §3 — 구현 필요(범위 결정 먼저) |

**세 기능 모두 "코드가 고장난" 것이 아니다.** 두 개는 설정이 없고, 하나는 애초에 만든 적이 없다.

---

## 1. Google 로그인 — 설정 없음

### 어디서 막히는가

```
프론트 login.html "Google로 계속하기"
  → location.href = /oauth2/authorization/google      ✅ 호출됨
  → nginx 프록시 → yogob-api                          ✅ 도달함
  → GoogleLogin.requireEnabled()                      ❌ 여기서 막힘
       if (!enabled) throw 503 "Google 로그인 설정을 확인해 주세요."
```

실제 응답(2026-09-16 확인):

```
GET https://yogob.fly.dev/oauth2/authorization/google
→ HTTP 503
  {"error":{"code":"YGB-AUTH-503","message":"Google 로그인 설정을 확인해 주세요."}}
```

`enabled`는 `GOOGLE_AUTH_ENABLED`에서 온다(`application.properties:12`). 배포된 secret 목록에
**`GOOGLE_AUTH_ENABLED`·`GOOGLE_CLIENT_ID`·`GOOGLE_CLIENT_SECRET`·`GOOGLE_REDIRECT_URI`가 전부 없다.**

코드는 완성돼 있다 — `SecurityConfig.googleRegistration`이 클라이언트를 등록하고,
`GoogleLogin.success`가 콜백을 처리하며, 계정 연결·충돌 처리까지 있다(`docs/auth.md`).
값이 없어서 그 빈(Bean) 자체가 만들어지지 않을 뿐이다(`@ConditionalOnProperty`).

### 조치

**(1) Google Cloud Console에서 "웹 애플리케이션" 클라이언트를 만든다.**
기존 클라이언트는 **데스크톱 유형**이라 쓸 수 없다(2026-09-13 확인, `docs/state.md`).

- API 및 서비스 → 사용자 인증 정보 → 사용자 인증 정보 만들기 → **OAuth 클라이언트 ID**
- 애플리케이션 유형: **웹 애플리케이션**
- 승인된 리디렉션 URI에 **정확히** 이 값을 넣는다 (한 글자라도 다르면 Google이 거부한다):

```
https://yogob.fly.dev/login/oauth2/code/google
```

- OAuth 동의 화면에서 범위는 `openid`, `email`만 있으면 된다(코드가 그 둘만 요청한다).

**(2) secret 4개를 설정한다.** 클라이언트 보안 비밀번호는 직접 입력한다:

```bash
flyctl secrets set -a yogob-api \
  GOOGLE_AUTH_ENABLED=true \
  GOOGLE_CLIENT_ID='<Cloud에서 발급받은 클라이언트 ID>' \
  GOOGLE_CLIENT_SECRET='<클라이언트 보안 비밀번호>' \
  GOOGLE_REDIRECT_URI='https://yogob.fly.dev/login/oauth2/code/google'
```

secret을 설정하면 앱이 자동 재시작된다.

### 확인 방법

```bash
curl -sS -i https://yogob.fly.dev/oauth2/authorization/google | head -3
```

- **성공**: `HTTP/2 302` + `location: https://accounts.google.com/o/oauth2/v2/auth?...`
- 실패하고 503이 그대로면 secret 이름 오타를 의심한다.

### 주의할 함정

- `GOOGLE_REDIRECT_URI`는 코드가 **경로까지 검증**한다(`SecurityConfig:44`).
  `/login/oauth2/code/google`이 아니면 앱이 기동에 실패한다. query·fragment도 불가.
- 로그인 성공 후 이동할 곳은 `AUTH_RETURN_URL`이다. 현재 `https://yogob.fly.dev/account.html`로
  설정돼 있고, 프론트 `account.js`가 `#auth=success`를 읽어 처리한다. 바꿀 필요 없다.

---

## 2. 가입 메일 발송 — 비밀번호 한 개만 없음

### 어디서 막히는가

```
프론트 signup.html → POST /api/v1/auth/email/verification   ✅ 호출됨
  → AuthEmail.request()
       if (!enabled || senders.getIfAvailable() == null || from.isBlank()) throw unavailable();
       ...
       send() → senders.getObject().send(message)
                  catch (MailException) → throw unavailable()   ❌ 여기서 막힘
```

실제 응답(2026-09-16 확인):

```
POST https://yogob.fly.dev/api/v1/auth/email/verification  {"email":"..."}
→ {"error":{"code":"YGB-AUTH-MAIL","message":"본인 확인 메일을 보낼 수 없습니다."}}
```

배포된 secret을 보면 `AUTH_EMAIL_ENABLED`·`AUTH_EMAIL_FROM`·`SMTP_HOST`·`SMTP_PORT`·
`SMTP_USERNAME`·`SMTP_AUTH`·`SMTP_STARTTLS_REQUIRED`는 **전부 있고 `SMTP_PASSWORD`만 없다.**
Gmail SMTP가 인증을 거부해 `MailException`이 나고, 코드가 이를 503으로 바꾼다.

### 조치

**(1) Gmail 앱 비밀번호를 발급받는다.** 계정 비밀번호가 아니다.

- Google 계정 → 보안 → **2단계 인증을 먼저 켠다**(켜야 앱 비밀번호 메뉴가 생긴다)
- 보안 → 앱 비밀번호 → 앱 이름 입력 → **16자리** 발급
- 공백 없이 붙여 쓴다(`abcd efgh ijkl mnop` → `abcdefghijklmnop`)

**(2) 설정한다.**

```bash
flyctl secrets set -a yogob-api SMTP_PASSWORD='<16자리 앱 비밀번호>'
```

### 확인 방법

```bash
CSRF=$(curl -sS -c /tmp/c.txt https://yogob.fly.dev/api/v1/auth/csrf)
TOKEN=$(echo "$CSRF" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['token'])")
HEADER=$(echo "$CSRF" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['headerName'])")
curl -sS -b /tmp/c.txt -X POST https://yogob.fly.dev/api/v1/auth/email/verification \
  -H "Content-Type: application/json" -H "$HEADER: $TOKEN" \
  -d '{"email":"본인메일@gmail.com"}'
```

- **성공**: `{"data":{"message":"..."},"warnings":[]}` + 실제 메일 수신
- 메일의 링크는 `https://yogob.fly.dev/account.html#action=signup&token=...` 형식이고,
  `account.js`가 그 토큰으로 비밀번호를 설정해 가입을 끝낸다. **10분·1회용**이다.

### 주의할 함정

- **응답이 성공이어도 메일이 안 올 수 있다.** BE는 존재하지 않는 주소에도 같은 응답을 준다
  (계정 존재 여부를 노출하지 않기 위한 설계, `AuthEmail.request` 주석). 실제 수신으로 확인해야 한다.
- 비밀번호는 **15자 이상**이어야 한다(`model.js:validPassword`). 짧으면 가입 마지막 단계에서 막힌다.
- Gmail 무료 계정은 하루 발송 한도가 있다. 데모용으로는 충분하다.
- 같은 이메일로 **3회**까지만 요청할 수 있다(`AuthRateLimit`, `limits.check("mail:"+email, 3)`).

---

## 3. Google 캘린더 — 구현이 없다

### 확인 결과

**"작동을 안 한다"가 아니라 만든 적이 없다.** 설정으로 해결되지 않는다.

- BE: `grep -rn "calendar" src/main/java` → **0건**. 계약(`docs/architecture.md §3`)에도 없다.
- 프론트 `calendar.html:28`에 "Google 캘린더 연동하기" 버튼이 있지만, `calendar.js:106`이 하는 일은
  안내문 토글뿐이다:

```js
$('gcal').addEventListener('click', () => { $('gcal-note').hidden = !$('gcal-note').hidden; });
```

```html
<p class="notice-line" id="gcal-note" hidden>Google 캘린더 연동은 준비 중이에요. 아래 일정을 참고해 직접 등록해 주세요.</p>
```

화면의 4단계 가이드와 날짜(`STEPS`·`EVENTS` 상수)도 **고정된 예시**다. 추천 결과와 무관하게
"준비 → 가입 → 구독 정리 → 완료"가 항상 같은 간격(0·3·8·14·21일)으로 찍힌다.

### 조치 — 범위를 먼저 정해야 한다

세 가지 선택지가 있고 비용이 크게 다르다.

**(A) 링크 방식 — 서버 작업 0, 인증 불필요** *(권장)*

Google 캘린더는 URL 파라미터로 일정 추가 화면을 연다. OAuth도 API 키도 필요 없다.

```
https://calendar.google.com/calendar/render?action=TEMPLATE
  &text=<일정 제목>&dates=<YYYYMMDDTHHMMSSZ/YYYYMMDDTHHMMSSZ>&details=<설명>
```

버튼을 누르면 새 탭에서 Google 캘린더 추가 화면이 열리고 사용자가 저장한다.
`.ics` 파일 내려받기를 같이 주면 애플/아웃룩 캘린더도 커버된다. **프론트만 고치면 된다.**

**(B) API 방식 — OAuth 범위 확대 필요**

`https://www.googleapis.com/auth/calendar.events` 범위를 받아 서버가 일정을 직접 만든다.
필요한 것: OAuth 동의 화면에 범위 추가 → **Google 심사**(민감 범위) → 리프레시 토큰 저장 →
토큰 갱신·폐기 처리. 심사에 시간이 걸리고, 토큰을 저장하면 개인정보 항목이 늘어
`docs/privacy.md`의 처리 인벤토리·보유기간을 갱신해야 한다.

**(C) 지금은 빼기**

버튼을 감추고 날짜 안내만 남긴다. 데모에서 눌렀을 때 아무 일도 안 일어나는 것보다 낫다.

### 부수 문제

(A)든 (B)든 **넣을 날짜가 없다.** 현재 캘린더의 날짜는 오늘 기준 고정 오프셋이다.
의미 있는 일정은 `GET /api/v1/me/switch-timing`이 주는 **약정 만료일·회수 시점**인데,
이건 회원 + 현재 요금제 저장이 선행돼야 한다(`docs/state.md` 미결정 항목).
비회원 흐름에서는 "언제" 옮길지를 서버가 모른다.

---

## 조치 순서 제안

1. **§2 메일** — 명령 한 줄. 가입·비밀번호 재설정이 즉시 살아난다.
2. **§1 Google 로그인** — Cloud 클라이언트 발급이 필요해 10~20분. 리디렉션 URI만 정확하면 된다.
3. **§3 캘린더** — 범위 결정 후 착수. (A)라면 프론트 작업 반나절, (B)는 심사 때문에 데모 전 불가능.

§1·§2는 **코드 변경이 필요 없다.** §3만 구현 작업이다.
