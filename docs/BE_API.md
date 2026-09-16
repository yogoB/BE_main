# 요고비 BE API 명세서

> 현재 코드 기준(2026-09-15) 정리본. 계약 원본은 `docs/architecture.md §3`(사람 관리). 이 문서는 프론트 연동용 참고본이다.

## 기본 정보

D-18 변경: 우체국·스마트초이스 연동과 `CostResult.priceCrossCheck`를 제거했다.
카탈로그 목록·신규 선택은 활성 상품만 사용하며, 기존 회원의 참조는 판매 종료 후에도 보존한다.

## 정보 오류 제보 — 구현

`POST /api/v1/catalog/reports`는 비회원도 이용한다. 먼저 `GET /api/v1/auth/csrf`에서 받은 쿠키와
토큰을 유지하고, 응답의 `headerName`을 헤더 이름으로 사용한다. 두 요청 모두 `credentials: include`가 필요하다.
회원 JWT는 필수가 아니며 CSRF 토큰은 필수다.

```json
{
  "targetType": "MOBILE_PLAN",
  "targetId": 42,
  "field": "PRICE",
  "description": "공식 요금표의 월 기본료와 다릅니다.",
  "sourceUrl": "https://provider.example/pricing"
}
```

| 필드 | 허용 값 |
|---|---|
| targetType | `MOBILE_PLAN`, `SUBSCRIPTION_SERVICE`, `SUBSCRIPTION_TIER`, `BUNDLE_PRODUCT` |
| targetId | 실제 존재하는 대상의 양의 정수 ID |
| field | `PRICE`, `DATA`, `BENEFIT`, `AVAILABILITY`, `OTHER` |
| description | 공백만 불가, 1~2,000자. 개인정보를 요청하지 않는다 |
| sourceUrl | 선택(null/빈 문자열 허용). 최대 2,000자 HTTPS, 인증정보·query·fragment 불가 |

```json
{
  "data": {"id": "c03c8c81-e012-4fa4-b31d-08fefeb9ba50", "status": "PENDING"},
  "warnings": []
}
```

성공 HTTP 200. 입력 오류 400, CSRF 누락/불일치 403, 대상 없음 404, 동일 접속 IP의 15분간 5회 초과는 429다.
서버가 출처 링크를 자동 조회하지 않는다. 공개 조회·수정 API는 없고, GET 요청으로 제보 본문을 열람할 수 없다.
화면은 접수 완료를 안내하며 가격 변경 완료로 표시하지 않는다. 운영자가 검수한 CSV를 발행해야 가격이 바뀐다.
내장 `/` 화면의 요금제·추천·계산 카드에서 같은 흐름을 사용한다. 제보는 90일 경과 후 정기 파기한다.
운영 절차: [CSV와 제보 관리](catalog-data.md).

## 연결 설정

| 항목 | 값 |
|---|---|
| 배포 Base URL | `https://yogob.fly.dev` |
| 로컬 Base URL | `http://localhost:8080` |
| 공통 프리픽스 | `/api/v1` |
| 인증 | 추천·계산기·카탈로그·챗봇은 비회원 공개. `/me`와 계정 관리는 HttpOnly JWT 쿠키 + CSRF. 상세 `docs/auth.md` |
| 콘텐츠 타입 | `application/json` (UTF-8) |
| CORS | 정확한 프론트 오리진만 허용(`YOGOBI_CORS_ALLOWED_ORIGINS`). 회원 요청은 `credentials: include`; wildcard 금지 |

**프론트는 이 문서의 BE API만 호출한다.** AI의 `/parse`·`/narrate`·`/ocr`를 직접 호출하지 않는다.
AI 연결과 내부 인증은 BE가 담당하며 프론트에는 AI 주소·내부 토큰·모델 API 키가 필요 없다.
사용자 인증·대화 이력 저장도 BE 책임이다. 자체·Google 회원 인증은 구현됐고 대화 이력 저장은 후속 작업이다.

---

## 공통 응답 규약

### 성공

```json
{
  "data": { },
  "warnings": [
    { "code": "YGB-EXT-001", "message": "..." }
  ]
}
```

- 실제 페이로드는 항상 `data` 안에 있다.
- `warnings`는 보통 빈 배열. 외부 연동(AI 등) 장애처럼 **추천은 정상이지만 부가 기능이 실패**했을 때만 채워진다.

### 에러

```json
{
  "error": { "code": "YGB-REQ-001", "message": "...", "field": "wantedServiceIds" }
}
```

- `field`는 입력 검증 오류일 때만 채워지고, 아니면 `null`.

### 에러 코드

| 코드 | HTTP | 의미 |
|---|---|---|
| `YGB-REQ-001` | 400 | 필수 입력 누락·형식 오류 |
| `YGB-CAL-001` | 422 | 계산 가능한 조합 없음 — **현재 어떤 엔드포인트도 반환하지 않는다**(D-17로 추천은 200+안내로 바뀜) |
| `YGB-CAT-001` | 404 | 요금제 없음 |
| `YGB-CAT-503` | 503 | 카탈로그 원본 파일 경로 미설정 — **편집만** 불가. 조회는 정상 |
| `YGB-EXT-001` | 200 + `warnings` | 외부(AI) 연동 실패 — 추천 자체는 정상 반환 |

---

## Enum

| 이름 | 값 | 쓰임 |
|---|---|---|
| `provenance` | `OFFICIAL` · `DERIVED` · `USER_PROVIDED` · `ESTIMATED` | 금액 항목의 출처 |
| `accuracy` | `FULL` · `PARTIAL` | 선택 입력이 다 찼는지 |
| `contractType` | `NONE` · `SELECTIVE_25` · `DEVICE_SUBSIDY` | 약정 유형(요청) |
| `networkType` | `5G` · `LTE` · `3G` | 망 종류(요청). 응답/DB는 `FIVE_G`·`LTE`·`THREE_G` |
| `benefitType` | `FREE` · `FIXED_DISCOUNT` · `RATE_DISCOUNT` · `BUNDLE_INCLUDED` | 제휴 혜택 형태 |
| chat `status` | `RECOMMENDED` · `NEEDS_INPUT` · `FILTER_FALLBACK` | 챗봇 응답 상태 |
| detection `rule` | `BENEFIT_OVERLAP` · `TIER_DUPLICATE` · `BUNDLE_OVERLAP` | 중복/낭비 탐지 규칙 |
| switch-timing `status` | `SWITCH_NOW` · `WAIT_UNTIL_EXPIRY` · `NO_BENEFIT` | 변경 시점 판정 |
| consent `item` | `ESSENTIAL`(철회 불가) · `MARKETING` | 수집·이용 동의 항목 |

**구독 서비스 ID 고정값**: 1 넷플릭스 · 2 디즈니+ · 3 티빙 · 4 웨이브 · 5 왓챠 · 6 유튜브 프리미엄

---

## 1. 추천 — `POST /api/v1/recommendations`

필터·챗봇이 공유하는 무상태 추천. 데이터 요구량을 만족하는 후보 요금제마다 실질월비용을 계산해 **싼 순으로 상위 5개**를 반환한다.

### 요청

```json
{
  "required": {
    "monthlyDataGb": 20,
    "wantedServiceIds": [1, 5]
  },
  "optional": {
    "currentCarrier": "SKT",
    "networkType": "5G",
    "contractType": "SELECTIVE_25",
    "hasFamilyBundle": true
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `required.monthlyDataGb` | int (≥1) | ✅ | 월 데이터 사용량(GB) |
| `required.wantedServiceIds` | long[] (≥1개) | ✅ | 원하는 구독 서비스 ID |
| `optional.currentCarrier` | string | | 현재 통신사 |
| `optional.networkType` | `5G`\|`LTE`\|`3G` | | 망 종류(필터) |
| `optional.contractType` | contractType | | 약정 유형 |
| `optional.hasFamilyBundle` | bool | | 가족 결합 여부 |

`optional`의 빈 필드는 응답 `missingInputs`로 안내된다.

### 응답 200

```json
{
  "data": {
    "accuracy": "PARTIAL",
    "missingInputs": [
      { "field": "hasFamilyBundle", "impact": "가족 결합 시 결합할인이 추가로 반영돼요", "howToFind": "통신사 마이페이지 > 결합 상품" }
    ],
    "results": [
      {
        "planId": 5,
        "planName": "5G OTT택1",
        "carrier": "LGU+",
        "monthlyTotal": 45000,
        "baseline": 63500,
        "monthlySavings": 18500,
        "annualSavings": 222000,
        "breakdown": [
          { "label": "5G OTT택1 기본료", "amount": 50000, "provenance": "OFFICIAL", "note": null },
          { "label": "약정할인", "amount": -5000, "provenance": "DERIVED", "note": null },
          { "label": "넷플릭스 스탠다드", "amount": 0, "provenance": "OFFICIAL", "note": "제휴 혜택 적용" }
        ]
      }
    ],
    "reasons": [
      "따로 내시던 넷플릭스 스탠다드가 요금제에 포함돼 있어요.",
      "약정할인으로 월 5,000원이 빠져요."
    ]
  },
  "warnings": []
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `accuracy` | accuracy | 선택 입력이 다 차면 `FULL`, 아니면 `PARTIAL` |
| `missingInputs[]` | object[] | 비어 있는 선택 입력 안내 (`field`·`impact`·`howToFind`) |
| `results[]` | object[] | 실질월비용 오름차순 상위 5개 |
| `results[].monthlyTotal` | long(원) | 실질 월 총비용(유일한 비교 기준) |
| `results[].baseline` | long(원) | 할인 없이 정가 합 |
| `results[].monthlySavings` / `annualSavings` | long(원) | `baseline - monthlyTotal` / ×12 |
| `results[].breakdown[]` | object[] | 항목별 내역. `amount` 할인은 음수. `provenance`·`note` |
| `reasons[]` | string[] | 1순위 조합에 대한 AI 큐레이션 사유 0~3개(화면 "왜 나에게 이 상품이 추천됐나요?"). **보조 정보** — AI 장애 시 빈 배열, 결과·금액은 그대로. 요청에 없는 금액이 섞인 줄은 BE·AI가 폐기(D-19). 결손으로 `results`가 비면 빈 배열 |

### 카탈로그 결손은 오류가 아니다 (D-17 · G-12)

CSV에 없는 것을 물어도 **200으로 답한다.** 아는 것으로 계산하고, 모르는 것은 `missingInputs`로 안내한다.

| 상황 | 이전 | 현재 |
|---|---|---|
| 없는 `serviceId` 포함 | 400 `YGB-REQ-001` | **200** — 그 서비스만 계산에서 빼고 `missingInputs`에 `wantedServiceIds` 항목 |
| 조건 만족 요금제 0건 | 422 `YGB-CAL-001` | **200** — `results: []` + `missingInputs`에 `monthlyDataGb` 항목 |

두 경우 모두 `accuracy=PARTIAL`이며, 결손은 `catalog_candidate`에 `REQUESTED`로 기록돼 팀의 CSV 수집
우선순위가 된다(계산에는 쓰지 않는다). 기록 실패는 추천을 깨뜨리지 않는다(fail-soft).

### 에러

- `400 YGB-REQ-001` — `monthlyDataGb` 누락/≤0, `wantedServiceIds` **비었음**, 잘못된 `contractType`/`networkType`
- `422 YGB-CAL-001` — 이 엔드포인트는 더 이상 반환하지 않는다 (위 표 참고)

---

## 2. 계산기 — `POST /api/v1/calculator`

특정 조합(요금제 + 구독 등급들)의 총비용. 추천과 달리 **구독 등급을 직접 지정**한다(대표 등급 자동선정 없음).
> 사용자 노출 용어는 "구독 등급". API 필드명은 `tierId`/`tierIds` 그대로다(구독 티어 = 구독 등급).

### 요청

```json
{
  "planId": 5,
  "tierIds": [8, 12],
  "optional": { "contractType": "NONE", "hasFamilyBundle": false }
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `planId` | long | ✅ | 통신 요금제 ID |
| `tierIds` | long[] (≥1개) | ✅ | 구독 등급 ID들 (구독 티어) |
| `optional` | object | | 추천과 동일(`contractType`·`hasFamilyBundle`·`currentCarrier`·`networkType`) |

### 응답 200

```json
{
  "data": {
    "accuracy": "FULL",
    "missingInputs": [],
    "result": {
      "planId": 5, "planName": "...", "carrier": "...",
      "monthlyTotal": 48333, "baseline": 57733,
      "monthlySavings": 9400, "annualSavings": 112800,
      "breakdown": [ { "label": "...", "amount": 15000, "provenance": "OFFICIAL", "note": null } ]
    }
  },
  "warnings": []
}
```

`result`는 추천의 `results[]` 원소와 같은 구조(단건).

### 에러

- `400 YGB-REQ-001` — `planId` 누락, `tierIds` 비었거나 없는 구독 등급 ID
- `404 YGB-CAT-001` — 요금제 없음

---

## 3. 카탈로그 조회 (GET)

### 3-1. 구독 서비스 + 구독 등급 — `GET /api/v1/catalog/services`

```json
{
  "data": [
    {
      "id": 1, "name": "넷플릭스", "category": "OTT",
      "officialUrl": "https://www.netflix.com/signup/planform",
      "tiers": [
        { "id": 2, "name": "스탠다드", "price": 13500, "currency": "KRW", "taxIncluded": true,
          "krwEstimate": null, "krwRateDate": null,
          "concurrentStreams": 2, "quality": "FHD", "note": null },
        { "id": 94, "name": "Pro", "price": 20, "currency": "USD", "taxIncluded": false,
          "krwEstimate": 29901, "krwRateDate": "2026-09-15",
          "concurrentStreams": null, "quality": null, "note": "확장 사용량" }
      ]
    }
  ],
  "warnings": []
}
```

`price` 는 **`currency` 단위의 공식 표기 금액**이다(`KRW` | `USD`).
해외 결제 등급은 원화 확정 금액이 없어 `krwEstimate`(환율 환산, **ESTIMATED**)와 기준일 `krwRateDate` 를 함께 준다.
`taxIncluded=false` 는 **표기가가 세금 별도**라는 뜻이다(해외 사업자 관행). 이때 `krwEstimate` 에는 부가세 10%가 들어 있다
— `$20 × 1.1 × 1359.15 = 29,901원`. 표기가(`price`)에는 세금을 섞지 않는다. 공식 표기가가 출처이기 때문이다.
환율은 하루 1회 배치로만 갱신하며(요청 경로에서 외부 호출 없음) 값이 없으면 두 환산 필드는 `null` 이다 — 0원으로 적지 않는다.
**환산값은 표시 전용이다.** 추천·계산기는 원화 확정 등급만 계산에 넣고, 빠진 것은 `missingInputs` 로 알린다(G-17).

### 3-2. 통신 요금제 — `GET /api/v1/catalog/plans`

```json
{
  "data": [
    {
      "id": 1, "carrier": "SKT", "name": "5G 넷플릭스팩", "networkType": "FIVE_G",
      "basePrice": 55000, "dataMb": 999999, "voiceMin": 999999, "smsCnt": 999999,
      "contractDiscount12m": 2400, "contractDiscount24m": 4800
    }
  ],
  "warnings": []
}
```

> `voiceMin`·`smsCnt`는 **null일 수 있다** — 공식 표기에 수량이 없는 요금제("기본제공" 등)의 미확인 값이다.
> `0`(미제공)과 구분한다. 두 값은 추천 후보 선별·금액 계산에 쓰지 않는다.
> 제휴 혜택(D3) 시드 전에는 `benefits`가 비어 있다.

### 3-3. 요금제별 제휴 혜택 — `GET /api/v1/catalog/plans/{id}/benefits`

```json
{
  "data": [
    {
      "serviceId": 1, "serviceName": "넷플릭스", "tierId": 2,
      "benefitType": "FREE", "discountValue": null,
      "exclusive": false, "exclusiveGroup": null
    }
  ],
  "warnings": []
}
```

- `discountValue`: `FIXED_DISCOUNT`면 원 단위 정수, `RATE_DISCOUNT`면 0~1, 그 외 `null`.
- `exclusive`가 `true`면 `exclusiveGroup` 안에서 **택1**.
- 없는 요금제 ID → `404 YGB-CAT-001`.

---

## 4. 챗봇 — `POST /api/v1/chat/messages`

자연어 한 문장을 받아 AI 서버로 파라미터를 추출(`/parse`)하고, 필터와 **동일한 추천 엔진**을 태운 뒤 설명을 붙여(`/narrate`) 반환한다. 금액·순서는 추천 엔진 값 그대로다.

> AI 서버(`AI_SERVER_URL`, 기본 `http://localhost:8000`)가 필요하다. AI가 죽어도 앱은 정상이며 아래처럼 폴백한다.

서버 운영 설정: BE와 AI에 동일한 `AI_INTERNAL_TOKEN`이 필요하다. 프론트 요청에는 이 값을 넣지 않는다.
내부 토큰이 없거나 불일치해도 프론트 응답은 아래 상태 형식을 유지하며 `FILTER_FALLBACK`으로 안내한다.

### 요청

```json
{ "text": "데이터 20기가에 넷플릭스 보고 싶어" }
```

| 필드 | 타입 | 제약 |
|---|---|---|
| `text` | string | 1~4,000자. 객체에 `text` 키 하나만 |

### 응답 200

```json
{
  "data": {
    "status": "RECOMMENDED",
    "message": "가장 저렴한 조합은 ... 입니다.",
    "recommendation": { "accuracy": "PARTIAL", "missingInputs": [], "results": [ ] }
  },
  "warnings": []
}
```

| `status` | 의미 | `recommendation` |
|---|---|---|
| `RECOMMENDED` | 추천 성공 | 추천 응답 본문(§1) |
| `NEEDS_INPUT` | 입력이 부족해 되물음 | `null` |
| `FILTER_FALLBACK` | 대화 처리 어려움 → 필터로 안내 | `null` |

- AI 설명만 실패하면 `status=RECOMMENDED`로 목록은 유지되고 `message`가 대체 문구가 된다.
- AI 연결 자체가 실패하면 `warnings`에 `YGB-EXT-001`이 실린다.
- 에러: `400 YGB-REQ-001` — `text` 누락/형식 오류/길이 초과.

---

## 회원 인증

**가입·로그인은 Google OAuth 하나뿐이다(D-34).** 자체 이메일·비밀번호 가입, 복구 코드, 계정 연결은
2026-09-17 에 제거했다. 사용자가 보는 것은 **버튼 하나**이고, Google `sub` 가 이미 있으면 로그인,
없으면 가입이다 — 서버가 알아서 나눈다.

우리는 **회원 비밀번호를 보관하지 않는다.** 따라서 비밀번호 재설정·복구 코드도 없다(잊을 것이 없다).
`localLogin` 은 항상 `false`, `googleLogin` 은 항상 `true` 다. 운영자 백오피스 로그인(`/api/v1/admin/login`)은
별개이며 비밀번호를 쓴다(D-32).

JWT 절대 수명 15분·유휴 제한 5분(refresh 없음). 상태를 바꾸는 요청은 모두 CSRF 토큰이 필요하다.
상세 실행법·설정은 [auth.md](auth.md), 공격 검증은 [auth-security.md](auth-security.md).

| Method | Path | 요청 | 응답 |
|---|---|---|---|
| GET | `/api/v1/auth/csrf` | — | `{headerName,token}` — 공개. 헤더 이름은 `X-CSRF-TOKEN` |
| GET | `/oauth2/authorization/google` → `/login/oauth2/code/google` | — | **유일한 가입·로그인 경로.** 완료 후 `AUTH_RETURN_URL#auth=success\|failed\|account-conflict` 로 리다이렉트 |
| POST | `/api/v1/auth/logout` | 인증+CSRF | `{loggedOut:true}` — 현재 로그인만 폐기 |
| POST | `/api/v1/auth/logout-all` | 인증+CSRF | `{loggedOut:true}` — 이 회원의 모든 로그인 폐기 |
| GET | `/api/v1/me` | 인증 | 현재 회원 |
| DELETE | `/api/v1/me` | 인증+CSRF | `{deleted:true}` — 탈퇴. 법정 보존 사본만 남는다 |
| POST | `/api/v1/me/nickname` | `{nickname}`, 인증+CSRF | 현재 회원 (D-22) |
| GET | `/api/v1/me/sessions` | 인증 | 로그인 세션 목록(`current` 플래그 포함) |
| DELETE | `/api/v1/me/sessions/{sessionId}` | 인증+CSRF | `{revoked:true}`. 남의 세션은 404 |

같은 이메일에 **다른 Google `sub`** 로 들어오면 자동 병합하지 않고 `#auth=account-conflict` 로 돌려보낸다.

**인증 에러 코드**: `YGB-AUTH-001` 401 · `YGB-AUTH-409` 409(계정 충돌) · `YGB-AUTH-DUP-NICK` 409 ·
`YGB-AUTH-403` 403(CSRF·권한) · `YGB-AUTH-404` 404(세션 없음) · `YGB-AUTH-429` 429(IP 15분 40회 초과) ·
`YGB-AUTH-503` 503(`JWT_SECRET`·Google 미설정).

> **배포 전 확인:** Google 로그인이 유일한 입구이므로 OAuth 앱의 **게시 상태가 곧 회원 상한**이다.
> `openid`·`email` 은 민감 스코프가 아니라 검증 없이 Production 게시가 되지만, Testing 모드로 남아 있으면
> 동의화면에 등록한 **테스트 사용자 100명**이 전체 한도가 된다.

## 5. 회원 데이터 (`/api/v1/me/**`)

모든 `/me` 계열은 **인증 필수**(ROLE_MEMBER, HttpOnly JWT 쿠키). 조회·변경은 **인증된 Principal의 userId만** 사용해
객체 단위 권한을 강제한다(남의 데이터 조회·수정 불가). 상태를 바꾸는 요청(POST/DELETE)은 **CSRF 토큰**이 필요하다.
미인증은 401, CSRF 누락은 403.

### 5-1. 본인 구독 — `GET/POST /api/v1/me/subscriptions`, `DELETE /api/v1/me/subscriptions/{id}`

구독 금액(`monthlyPrice`)은 사용자가 실제 내는 값(`USER_PROVIDED`)이며 탐지·현재 지출 계산의 입력원이다.

**GET** 응답 200 — `data`는 본인 구독 배열:

```json
{ "data": [
  { "id": 12, "tierId": 2, "tierName": "넷플릭스 프리미엄", "monthlyPrice": 13500,
    "startedAt": "2026-09-14", "endedAt": null }
] }
```

**POST** 요청 `{ "tierId": 2, "monthlyPrice": 13500 }` → 응답 200 `data`는 생성된 구독 1건(위 View 형태).
`tierId` 누락·미존재, `monthlyPrice` null·음수 → **400** `YGB-REQ-001`.

**DELETE** `/subscriptions/{id}` → 응답 200 `{ "data": { "removed": true } }`.
없거나 남의 구독이면 **404** `YGB-SUB-404`.

### 5-2. 현재 요금제 설정 — `POST /api/v1/me/current-plan`

요청 `{ "planId": 1 }` → 응답 200 `{ "data": { "updated": true } }`.
`planId` 누락 → 400 `YGB-REQ-001`, 없는 요금제 → **404** `YGB-CAT-001`. 변경 시점(5-4)의 선행 조건이다.

### 5-3. 결제내역 업로드 — `POST /api/v1/me/payments/import`

요청 본문은 [데이터 문서 §6](data.md#6-mock-마이데이터-표준-형식-모방)의 Mock 마이데이터 JSON(카드 승인내역).
`status=01`(승인)만 저장하고 취소·기타는 제외한다. 각 항목은 `currency_code=KRW`·0 이상 long 정수 금액·
유효한 14자리 일시(`yyyyMMddHHmmss`)를 검증한다. 저장은 외부 결제 분석본(`payment_record`)에만 이뤄지고
자동 구독 생성은 하지 않는다(가맹점 확인 전).

응답 200:

```json
{ "data": { "imported": 2, "recognized": 1, "unrecognized": ["배달의민족"] } }
```

- `imported` **이번에 새로 저장된** 건수(취소 제외), `recognized` 그중 가맹점→서비스 매칭 성공 건수.
- 미인식 가맹점은 `service_id=null`로 저장하고 `unrecognized`로 되돌려 **사용자에게 확인**을 요청한다(추측 매핑 금지).
- 형식 오류는 **400** `YGB-REQ-001`이며 전체 입력을 저장하지 않는다(부분 저장 없음).
- **재업로드는 중복을 만들지 않는다.** (회원, 가맹점 원문, 금액, 결제일, 출처)가 같으면 같은 결제로 보고 건너뛴다.
  같은 파일을 다시 올리면 `{ "imported": 0, "recognized": 0, "unrecognized": [] }` 이고 저장된 건수는 그대로다.
  업로드 항목에 승인번호가 없어 이 5개가 자연키다 — 같은 날 같은 금액의 별개 결제는 중복으로 흡수된다(알려진 한계).
- 법정 보존 사본은 자동 생성하지 않는다.

### 5-4. 변경 시점(회수기간) — `GET /api/v1/me/switch-timing`

읽기 전용. 회원의 **현재 요금제(5-2 저장분) + 활성 구독**으로 계산한 현재 실질월비용을 대상 요금제와 같은 조건으로 비교한다.

쿼리 파라미터:

| 이름 | 필수 | 기본 | 설명 |
|---|---|---|---|
| `targetPlanId` | ✅ | — | 비교 대상 요금제 ID |
| `switchingCost` | ✕ | 0 | 전환비용(위약금 등, 사용자 추정치) |
| `remainingContractMonths` | ✕ | 0 | 약정 잔여 개월(사용자 추정치) |

응답 200:

```json
{ "data": {
  "currentMonthlyCost": 68500, "targetMonthlyCost": 58500, "monthlySavings": 10000,
  "switchingCost": 0, "paybackMonths": 6, "remainingContractMonths": 12, "status": "SWITCH_NOW"
} }
```

- `status` = `SWITCH_NOW`(약정잔여 0 이거나 회수개월 < 약정잔여) · `WAIT_UNTIL_EXPIRY`(그 외) · `NO_BENEFIT`(월 절감 ≤ 0, `paybackMonths=null`).
- 현재 요금제 미설정 → **400** `YGB-REQ-001`(먼저 5-2 호출). `targetPlanId` 미존재 → 404 `YGB-CAT-001`.
- `switchingCost`·`remainingContractMonths` 음수, 회수 개월 Integer 초과 → 400. 활성 구독이 없어도 비교 가능.
- 현재 계산은 카탈로그 티어 가격 기준이며 저장한 실제 청구액·약정·가족결합을 완전히 반영하지 않는다.
  항목별 출처를 포함한 개인화 응답은 [후속 검토안](proposals/2026-09-15-service-direction.md)에 기록했다.

### 5-5. 중복 결제 탐지 — `GET /api/v1/me/detections`

요청 시 현재 구독·요금제 기준으로 **재탐지해 저장·반환**한다. 응답 200 `data`는 탐지 결과 배열:

```json
{ "data": [
  { "rule": "BENEFIT_OVERLAP", "targetRef": "넷플릭스", "wastedAmount": 13500 }
] }
```

`rule` = `BENEFIT_OVERLAP`·`TIER_DUPLICATE`·`BUNDLE_OVERLAP`, `wastedAmount`는 월 단위 낭비 금액(원).

### 5-6. 종료 예정 알림 — 미구현 (P2)

---

## 6. 개인정보·동의 (`/api/v1`)

### 6-1. 처리방침 — `GET /api/v1/privacy-policy` (공개)

인증 불필요. 응답 200 `data`:

```json
{ "data": {
  "version": "2026-09-12",
  "items": [ { "category": "...", "fields": ["..."], "purpose": "...", "legalBasis": "...", "retention": "결제내역 12개월·탐지결과 6개월..." } ],
  "dataSubjectRights": ["열람", "삭제", "..."]
} }
```

### 6-2. 내 동의 조회 — `GET /api/v1/me/consent` (인증)

응답 200 `data`는 동의 항목 배열:

```json
{ "data": [
  { "item": "ESSENTIAL", "policyVersion": "2026-09-12", "agreed": true, "agreedAt": "2026-09-14T...Z", "withdrawnAt": null },
  { "item": "MARKETING", "policyVersion": "2026-09-12", "agreed": false, "agreedAt": null, "withdrawnAt": "..." }
] }
```

`ESSENTIAL`(필수)은 가입 시 기록되며 **철회 불가**(계약 이행 근거). `MARKETING`(선택)만 아래로 변경한다.

### 6-3. 마케팅 동의 변경 — `POST /api/v1/me/consent/marketing` (인증+CSRF)

요청 `{ "agree": true }` → 응답 200 `{ "data": { "agreed": true } }`. `agree`가 boolean이 아니면 400 `YGB-REQ-001`.

> **회원 탈퇴·데이터 삭제**는 `DELETE /api/v1/me`(회원 인증, [auth.md](auth.md)). 법정 보존 사본만 별도 보존.

## 7. 운영자 API (`/api/v1/admin/**`)

백오피스 전용이다. `POST /api/v1/admin/login` 만 공개이며 나머지는 운영자 세션이 필요하다.
프론트에서는 nginx 가 `/admin.(html|js)` 만 BE 로 프록시한다 — 일반 회원 화면에는 노출되지 않는다.

> **편집에는 `yogobi.catalog.combined-csv`(원본 파일 경로)가 필요하다.** 비어 있으면 **조회는 되지만**
> 제안·승인은 **503 `YGB-CAT-503`** 으로 거부된다. 승인은 제안을 FAILED 로 닫기 **전에** 막으므로,
> 경로를 채운 뒤 같은 제안을 그대로 승인할 수 있다. 2026-09-17 기준 **운영에는 설정돼 있지 않다.**

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/v1/admin/login` | 운영자 로그인 `{id,password}`. 실패는 401 하나로만 답한다 (D-32) |
| GET | `/api/v1/admin/session` | 관리자 여부 확인 |
| GET | `/api/v1/admin/dashboard` | 사용 지표 — 회원·카탈로그·검수·제보·엔드포인트 |
| POST | `/api/v1/admin/harvest/run` | 일일 수집 즉시 실행 (정기: 매일 09:00 KST) |
| POST | `/api/v1/admin/smartchoice/sweep` | 스마트초이스 스냅샷 스윕 즉시 실행 |
| POST | `/api/v1/admin/fx/refresh` | 환율 즉시 갱신(정기: 09:15 KST). 실패해도 이전 값 유지 — 응답 `updated` 로 구분 |
| GET | `/api/v1/admin/retention/pending` | 보유기간 만료 **건수만** 조회. 지우지 않는다 |
| POST | `/api/v1/admin/retention/purge` | 만료 개인정보 파기(정기: 04:00 KST). `{confirm:"파기"}` 없으면 400 — 되돌릴 수 없다 |
| GET | `/api/v1/admin/catalog` | 카탈로그 원본(합본 CSV) 데이터셋 목록·행 수 (D-24) |
| GET | `/api/v1/admin/catalog/{dataset}` | 데이터셋 전체 행 |
| POST | `/api/v1/admin/catalog/{dataset}` | 행 추가 **제안** — 202. 승인 전까지 반영 없음 (D-28) |
| PATCH | `/api/v1/admin/catalog/{dataset}/{key}` | 행 부분 수정 **제안** — 202 |
| DELETE | `/api/v1/admin/catalog/{dataset}/{key}` | 행 삭제 **제안** — 202 |
| GET | `/api/v1/admin/catalog/requests` | 제안 목록. `?status=PENDING\|APPROVED\|REJECTED\|FAILED` |
| POST | `/api/v1/admin/catalog/requests/{id}/approve` | 승인 — **이때 파일·DB 에 반영**. 재승인·검토 불일치는 409 (D-29) |
| POST | `/api/v1/admin/catalog/requests/{id}/reject` | 거절 — `{reason}`, 영영 반영하지 않는다 |
| GET | `/api/v1/admin/catalog/audit` | 원본 변경 이력(최신순, `?limit=1~500`) — 행위자·시각·전/후 행·결과 (D-27) |

전체 호출 흐름은 [시퀀스 다이어그램](diagrams/index.html)에서 확인한다.
