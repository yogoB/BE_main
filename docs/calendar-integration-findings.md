# Google 캘린더 연동 — 현재 방식과 API 연동을 하지 않은 이유

조사: 2026-09-17 KST. D-34(Google OAuth 전용 로그인)를 정하면서 전제를 다시 확인한 기록이다.

**결론 두 줄.**
현재 "캘린더에 담기"는 **링크 방식**이고 **Google 로그인을 기술적으로 요구하지 않는다.**
API 연동(우리가 사용자 캘린더에 직접 일정을 생성)은 **구현이 어려워서가 아니라 Google 심사 일정 때문에** 하지 않았다.

---

## 1. 지금 어떻게 동작하나 — 링크 방식

`front/src/calendar.js` 는 두 가지를 만든다. **Google API를 호출하지 않는다.**

| 경로 | 만드는 것 |
|---|---|
| Google 캘린더 | `https://calendar.google.com/calendar/render?action=TEMPLATE&text=…&dates=…&details=…` 를 새 창으로 연다 |
| 애플·아웃룩 | 일정 5개를 담은 `.ics` 파일을 브라우저에서 만들어 내려준다 (RFC 5545) |

브라우저에 이미 로그인된 Google 계정이 그 화면을 열고, **사용자가 "저장"을 누르면** 자기 캘린더에 들어간다.
액세스 토큰·스코프·권한 동의 화면이 **하나도 없다.** `.ics` 는 Google 계정조차 필요 없다.

## 2. 사용자에게 받는 값은 하나뿐이다

| 값 | 출처 |
|---|---|
| **약정 만료일** | `calendar.html` 의 `<input type="date">` — **유일한 직접 입력이고 비워도 된다** (비우면 오늘 기준, 골든 케이스 G-16) |
| 요금제명·월 요금·절감액 | `sessionStorage['yogobi:result']` — 추천 결과에서 이미 넘어온 값 |
| 전환 기준일 판정 | `GET /api/v1/me/switch-timing` — 만료일에서 계산한 **잔여 개월만** 넘긴다 |
| 일정 5개(−14/−7/−3/0/+30) | 우리가 계산 |
| Google 에게서 받는 것 | **없음** |

만료일은 서버로 가지 않는다. `calendar.js` 주석 그대로 — "서버는 이 날짜를 모른다".

## 3. 회원 전용인 것은 정책이지 기술 제약이 아니다

담기 버튼은 `GET /api/v1/me` 로 로그인을 확인한 뒤에만 열린다. 이건 우리가 정한 정책이다.
링크 방식 자체는 비회원도 문제없이 쓸 수 있다. **따라서 "캘린더 때문에 Google 로그인이 필요하다"는 성립하지 않는다.**

## 4. API 연동을 하려면 무엇이 필요한가 — 구현은 어렵지 않다

현재 코드는 `OidcUser` 만 쓰고 **액세스 토큰을 아예 건드리지 않는다.** 결손은 이것뿐이다.

| 결손 | 작업 |
|---|---|
| scope 가 `openid email` 뿐 | `https://www.googleapis.com/auth/calendar.events` 추가 |
| refresh token 을 요청하지 않음 | `access_type=offline` + `prompt=consent` |
| `HttpSessionOAuth2AuthorizedClientRepository` | 토큰이 세션과 함께 사라진다 → 영속 저장소로 교체 |
| 토큰 보관 수단 없음 | **refresh token 은 되읽어야 해서 해시로 못 둔다 → 암호화·키 관리가 새로 생긴다** |
| Calendar API 클라이언트 | `POST /calendar/v3/calendars/primary/events` |
| 연동 해제·파기 | `RetentionService`·탈퇴 경로에 항목 추가, 처리방침 갱신 |

Spring Security OAuth2 가 이미 붙어 있어 배선은 평이하다. 대략 250줄 규모다.
**테스트도 막히지 않는다** — `AuthSecurityTest` 가 이미 로컬 HTTP token/JWKS 서버를 띄워 state·nonce·PKCE·서명 검증을
실제로 통과시키고 있다. 같은 방식으로 Calendar API 를 스텁하면 우리 쪽 로직은 전부 검증된다.

## 5. 그런데 왜 하지 않았나 — Google 운영 게이트 셋

`calendar.events` 는 **민감(sensitive) 스코프**다. 여기서부터 우리가 통제할 수 없는 조건이 붙는다.

| # | 게이트 | 마감(2026-09-20) 기준 판단 |
|---|---|---|
| 1 | **검증 심사** — Production 게시에 Google 심사가 필요하다. 2026년에 `calendar.events` 로 **5주 넘게 심사 중**이라는 공개 보고가 있다 | **불가능.** 남은 시간이 3일이다 |
| 2 | **Testing 모드의 100명 한도** — 개발·테스트 단계는 검증이 면제되지만, OAuth 동의화면에 **테스트 사용자로 등록된 계정만** 승인할 수 있다 | 가능하지만 **데모를 볼 사람의 계정을 미리 등록**해야 하고, 빠뜨리면 그 자리에서 막힌다 |
| 3 | **Testing 모드의 refresh token 7일 만료** — 사용 빈도와 무관하게 고정 시계로 만료된다 | 3일 데모는 문제없지만 **그 뒤 연동이 조용히 끊긴다** |

즉 **기술이 아니라 일정이 막았다.** 심사만 통과하면 1·2·3이 모두 사라진다.

## 6. 하더라도 이렇게는 하지 말 것 — 로그인 스코프와 캘린더 스코프를 섞기

D-34 로 로그인이 Google 전용이 되므로, 로그인 요청에 `calendar.events` 를 같이 넣으면
**그냥 로그인하려는 사람에게도 캘린더 쓰기 권한 동의화면이 뜬다.** 나쁜 거래다.

분리한다 — 로그인은 `openid email` 그대로, 사용자가 "캘린더에 담기"를 **누른 그 순간에만**
증분 승인(`include_granted_scopes=true`)으로 `calendar.events` 를 추가 요청한다.
캘린더를 쓰지 않는 사용자는 권한 요구를 아예 보지 않는다.

또 하나 — 결과 화면의 신뢰 칩에 **"카드·계좌 연결 없음"** 이라고 적어 두었다.
캘린더 쓰기 권한이 붙은 refresh token 을 보관하는 것은 우리가 들고 있는 것의 성격이 한 단계 올라가는 일이다.
처리방침·파기 대상·탈퇴 경로에 반드시 포함해야 한다.

## 7. 링크 방식이 실제로 잃는 것

**사용자가 "저장"을 한 번 더 누르는 것뿐이다.** 일정 내용·날짜·설명은 API 연동과 동일하게 들어간다.
그 클릭 한 번을 없애려고 심사 5주·토큰 암호화·7일 만료·테스트 사용자 등록을 떠안는 것은 지금 범위에서 남는 장사가 아니다.

## 8. 다시 검토할 조건

아래 중 하나라도 성립하면 §4 를 그대로 실행하면 된다.

- 마감이 6주 이상 남았고 Google 검증을 시작할 수 있다
- 심사 없이 쓸 사용자가 100명 이하로 확정이고, 7일마다 재동의를 감수할 수 있다
- "우리가 대신 일정을 넣어준다"가 링크 대비 유의미한 가치라는 근거가 생긴다 (지금은 클릭 한 번이다)

## 출처

- [Sensitive scope verification — Google for Developers](https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification)
- [Choose Google Calendar API scopes](https://developers.google.com/workspace/calendar/api/auth)
- [OAuth verification under review for over 5 weeks (calendar.events) — Google Developer forums](https://discuss.google.dev/t/oauth-verification-under-review-for-over-5-weeks-calendar-events/376009)
- [Manage App Audience — 테스트 사용자 100명 한도](https://support.google.com/cloud/answer/15549945?hl=en)
- [Google OAuth refresh token 7일 만료](https://www.unipile.com/google-oauth-refresh-token/)
