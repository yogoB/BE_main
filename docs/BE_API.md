# 요고비 BE API 명세서

> 현재 코드 기준(2026-09-10) 정리본. 계약 원본은 `docs/architecture.md §3`(사람 관리). 이 문서는 프론트 연동용 참고본이다.

## 기본 정보

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
| `YGB-CAL-001` | 422 | 계산 가능한 조합(후보 요금제) 없음 |
| `YGB-CAT-001` | 404 | 요금제 없음 |
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

### 에러

- `400 YGB-REQ-001` — `monthlyDataGb` 누락/≤0, `wantedServiceIds` 비었거나 없는 서비스 ID, 잘못된 `contractType`/`networkType`
- `422 YGB-CAL-001` — 조건을 만족하는 요금제 없음

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
        { "id": 2, "name": "스탠다드", "price": 13500, "concurrentStreams": 2, "quality": "FHD", "note": null }
      ]
    }
  ],
  "warnings": []
}
```

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

> 실 요금제 시드(D3/D4) 전에는 빈 배열이거나 개발용 더미 데이터가 나온다.

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

자체 가입·로그인, Google 로그인, 현재 회원 조회, 로그아웃·모든 기기 로그아웃, 명시적 계정 연결,
이메일 검증 가입, 비밀번호 재설정, 로그인 세션 조회/폐기가 구현됐다. `/account.html`에서 확인할 수 있다.
가입은 메일의 토큰과 본인이 정한 비밀번호로 완료한다. 재설정은 CSRF 필수이며 자동 로그인하지 않는다.
JWT 절대 수명 15분·유휴 제한 5분. 상세 실행법·설정 점검은 `docs/auth.md`, 공격 검증은 `docs/auth-security.md`.
자체 가입은 이메일 검증 토큰이 필요하다(`AUTH_EMAIL_ENABLED=false`면 자체 가입·재설정은 503, Google만 동작).
요청 예시·엔드포인트·CSRF·쿠키·Google·SMTP 설정·에러 코드는 [회원 인증 명세](auth.md)를 따른다.
회원 JWT는 15분 만료(refresh 없음)이며 `GET /api/v1/me`는 현재 로그인한 회원만 반환한다.

## 아직 없는 것 (예정)

| 예정 엔드포인트 | 상태 |
|---|---|
| `GET/POST/DELETE /api/v1/me/subscriptions` | 내 구독 — 미구현 |
| `POST /api/v1/me/payments/import` | 결제내역 업로드 — 미구현 |
| `GET /api/v1/me/detections` | 중복 결제 탐지 조회 — 서비스 로직은 있음, HTTP 노출 전 |
| `GET /api/v1/me/switch-timing` · `alerts` | Phase 2 |

`/me` 계열은 인증된 회원만 접근 가능하다. 위 기능은 구현 후 현재 사용자 ID로 연결한다.
