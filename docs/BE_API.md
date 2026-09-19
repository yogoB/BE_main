# 요고비 BE API 명세서

> 현재 코드 기준 **2026-09-20** 정리본(BE v66). 계약 원본은 `docs/architecture.md §3`(사람 관리)이고
> 이 문서는 프론트 연동용 참고본이다. 구조·ERD 는 [`architecture.md`](architecture.md)·[`erd.md`](erd.md).

## 기본 정보

D-18 변경: 우체국 연동을 제거했다. 스마트초이스와 `CostResult.priceCrossCheck` 는 **D-23 으로 되살렸다**
(2026-09-16 사용자 승인) — 스마트초이스는 카탈로그 소스가 아니라 **검증 오버레이**다.
응답 필드의 계약은 `architecture.md` §3 이 원본이다.
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

### 상품 없는 제보 — `POST /api/v1/reports` (D-41)

화면·기능 오류처럼 특정 상품을 가리키지 않는 제보다. 인증·CSRF·비회원 규칙은 위와 같다.
프론트의 "오류 제보" 플로팅 버튼이 상품 카테고리는 `/catalog/reports` 로, 나머지는 여기로 보낸다.

```json
{"category":"SYSTEM","description":"결과 화면에서 다음 버튼이 눌리지 않아요","pageUrl":"/results","sourceUrl":null}
```

| 필드 | 허용 값 |
|---|---|
| category | `SYSTEM`(화면·기능 오류), `OTHER`(기타) |
| description | 공백만 불가, 1~2,000자. 개인정보를 요청하지 않는다 |
| pageUrl | 선택. `/`로 시작하는 우리 화면 경로(최대 2,000자). 다른 사이트 주소는 400 |
| sourceUrl | 선택. `/catalog/reports` 와 같은 HTTPS 규칙 |

오류 코드는 `/catalog/reports` 와 같다(404 는 없다). `service_report` 표에 저장하고 90일 뒤 파기하며,
백오피스 제보 지표는 두 표를 합쳐 센다.

응답에는 **리워드 쿠폰 1장**이 함께 온다(D-42).

```json
{"data":{"id":"<uuid>","status":"PENDING","coupon":{"code":"<uuid>","status":"UNUSED"}},"warnings":[]}
```

`coupon.code` 는 `id` 와 같은 값이다 — 제보 1건 = 쿠폰 1장이라 코드를 따로 두지 않았다.
**로그인 상태로 낸 제보만** 회원에 귀속된다. 비로그인 제보는 이 코드가 유일한 소유 증명이므로
화면에서 보관을 안내한다.

### 내 쿠폰함 — `GET /api/v1/me/coupons` (D-42)

회원 전용. 로그인 상태로 낸 제보의 쿠폰만 최신순으로 돌려준다.

```json
{"data":[{"code":"<uuid>","status":"UNUSED","issuedAt":"2026-09-17T05:12:00Z","usedAt":null}],"warnings":[]}
```

`status` 는 `UNUSED` / `USED`. **사용 API 는 없다** — 요금 분석은 지금 무료라 이 쿠폰이 해제하는 것이
아직 없기 때문이다(수익 모델은 범위 밖 — D-01). 보유기간은 제보와 같아 90일 뒤 함께 사라지고,
탈퇴하면 귀속이 끊겨 목록에서 빠진다.

## 연결 설정

| 항목 | 값 |
|---|---|
| 배포 Base URL | `https://yogob.fly.dev` |
| 로컬 Base URL | `http://localhost:8080` |
| 공통 프리픽스 | `/api/v1` |
| 인증 | 추천·계산기·카탈로그 **엔드포인트는 비회원 공개**. `/me`와 계정 관리는 HttpOnly JWT 쿠키 + CSRF. 단 **결과 리포트 화면은 로그인 후에만 그린다**(D-36 — 화면 게이트이며 API 는 그대로 공개다). 상세 `docs/auth.md` |
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
| detection `rule` | `BENEFIT_OVERLAP` · `TIER_DUPLICATE` · `BUNDLE_OVERLAP` | 중복/낭비 탐지 규칙 |
| switch-timing `status` | `SWITCH_NOW` · `WAIT_UNTIL_EXPIRY` · `NO_BENEFIT` | 변경 시점 판정 |
| consent `item` | `ESSENTIAL`(철회 불가) · `MARKETING` | 수집·이용 동의 항목 |

**구독 서비스 ID 고정값**: 1 넷플릭스 · 2 디즈니+ · 3 티빙 · 4 웨이브 · 5 왓챠 · 6 유튜브 프리미엄

---

## 1. 추천 — `POST /api/v1/recommendations`

무상태 추천. 데이터 요구량을 만족하는 후보 요금제마다 실질월비용을 계산해 **싼 순으로 상위 5개**를 반환한다.

### 요청

```json
{
  "required": {
    "monthlyDataGb": 20,
    "wantedServiceIds": [1, 5],
    "wantedTierIds": [2, 12]
  },
  "optional": {
    "currentCarrier": "SKT",
    "networkType": "5G",
    "contractType": "SELECTIVE_25",
    "hasFamilyBundle": true,
    "familyLineCount": 3,
    "familyBundleDiscountKrw": 11000,
    "currentPlanId": 42
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `required.monthlyDataGb` | int (≥1) | ✅ | 월 데이터 사용량(GB) |
| `required.wantedServiceIds` | long[] (≥1개) | ✅ | 원하는 구독 서비스 ID |
| `required.wantedTierIds` | long[] | | 사용자가 고른 구독 등급 ID. **비우면 서버가 대표 등급**(스탠다드 우선, 광고형 제외)을 고른다 — 이 필드를 안 보내던 호출은 그대로 동작한다. 요청한 서비스에 속하지 않는 등급 ID 는 무시한다 |
| `optional.currentCarrier` | string | | 현재 통신사 |
| `optional.networkType` | `5G`\|`LTE`\|`3G` | | 망 종류(필터) |
| `optional.contractType` | contractType | | 약정 유형 |
| `optional.hasFamilyBundle` | bool | | 가족 결합 여부 |
| `optional.familyLineCount` | int | | 결합 회선 수. **금액 계산에 쓰지 않는다** — 근거 문구용이다 |
| `optional.familyBundleDiscountKrw` | long (≥0) | | 가족결합 월 할인액. **사용자가 확인해 적어 준 금액**을 그대로 뺀다(`USER_PROVIDED`). 선택약정 25% 적용 **후**에 뺀다(`domain.md §4` 300). 결합 중이 아니면 무시하고, 요금보다 크면 0원까지만 깎는다. 음수는 400. **현재 통신사의 요금제에만 반영한다**(G-29) — 옮기면 결합이 풀리므로 다른 통신사 후보에서는 빼지 않는다 |
| `optional.currentPlanId` | long | | 지금 쓰는 요금제 ID(G-30). 응답 `current` 를 채우고, 그 요금제의 통신사를 **현재 통신사로 확정**한다(`currentCarrier` 보다 우선). 카탈로그에 없는 ID 는 400 이 아니라 `missingInputs` 안내 |

`optional`의 빈 필드는 응답 `missingInputs`로 안내된다.

### 응답 200

```json
{
  "data": {
    "accuracy": "PARTIAL",
    "missingInputs": [
      { "field": "hasFamilyBundle", "impact": "가족 결합 여부를 알려주시면 추천 정확도 표시가 올라가요 — 결합할인 금액은 아직 반영하지 않아요", "howToFind": "통신사 마이페이지 > 결합 상품" }
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
    "current": {
      "cost": { "planId": 7, "planName": "5G 언리미티드", "carrier": "SKT", "monthlyTotal": 82300, "baseline": 82300,
                "monthlySavings": 0, "annualSavings": 0, "breakdown": [] },
      "monthlySavings": 37300,
      "annualSavings": 447600
    },
    "candidateCount": 127,
    "message": "“SKT 5G OTT택1”의 실제 내시는 금액은 월 50,000원이에요. 아무 할인 없이 정가로 내는 금액은 월 68,500원이에요. …",
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
| `results[].monthlySavings` / `semiannualSavings` / `annualSavings` | long(원) | `baseline - monthlyTotal` / ×6 / ×12. `current` 에도 같은 세 값이 있다(D-51) |
| `results[].breakdown[]` | object[] | 항목별 내역. `amount` 할인은 음수. `provenance`·`note` |
| `results[].breakdown[].note` | string\|null | 꼬리표. `"제휴 혜택 적용"` · `"번들 적용"`(2026-09-20). **묶음은 음수 줄을 만들지 않는다** — 등급 여러 줄이 한 줄로 바뀌므로 음수만 보면 "할인 없음"으로 읽힌다 |
| `minimalChange` | object\|null | **번호이동 없이 요금제만 바꿀 때** 가장 싼 조합(D-55). `results[]` 와 같은 모양. 현재 통신사를 모르거나 그 통신사에 후보가 없으면 `null`. `results[0]` 과 같을 수 있다 |
| `current` | object\|null | 지금 쓰는 요금제로 **같은 구독을 유지했을 때**의 금액(G-30). `currentPlanId` 를 줬고 카탈로그에 있을 때만 |
| `current.cost` | object | `results[]` 와 같은 모양. 후보와 같은 계산기·같은 컨텍스트로 낸 값이다 |
| `current.monthlySavings` / `annualSavings` / `semiannualSavings` | long(원) | `current.cost.monthlyTotal - results[0].monthlyTotal` / ×12 / ×6. **지금이 더 싸면 음수 그대로** — 화면이 빼지 않도록 여기서 준다(원칙 2). **1순위 기준이다** — `minimalChange` 기준이 아니다 |
| `current.excluded` | object\|null | **지금 요금제가 후보에서 빠진 이유**(D-61, 2026-09-20). 후보였으면 `null`. `minimalChange` 가 `null` 이거나 지금보다 비싼 이유가 여기 있다(G-51) |
| `current.excluded.reason` | string | `INACTIVE` · `DATA` · `NETWORK` · `ELIGIBILITY`. 판정 순서는 후보 질의의 WHERE 절과 같다 |
| `current.excluded.planDataMb` / `requiredDataMb` | long | 비교한 두 값. `reason` 이 `DATA` 가 아니어도 사실로서 실린다 |
| `current.excluded.planNetwork` / `requiredNetwork` | string\|null | 같은 뜻. 사용자가 망을 안 골랐으면 `requiredNetwork` 는 `null` |
| `current.excluded.ageLimit` | string\|null | 가입 자격 표기 원문. **`reason` 이 `ELIGIBILITY` 일 때만 쓴다** — 제한 없는 요금제도 `"ALL"`·`"다이렉트"` 로 온다 |
| `candidateCount` | int | 정렬 대상이 된 후보 요금제 수. `results`에는 그중 상위 5개만 담긴다. 후보가 없으면 `null` |
| `message` · `reasons[]` · `notices[]` | — | **D-50(2026-09-18): 추천 본체에서는 항상 `null`·`[]`·`[]`.** 아래 `POST /api/v1/recommendations/narrate` 가 준다 |
| `message` | string | (narrate) 1순위 조합을 설명하는 3~5문장. **내레이터의 결정론적 템플릿이라 모델 키가 없어도 나온다.** AI에 닿지 못하면 `null`이고 화면은 자체 최소 문구로 대체한다 |
| `reasons[]` | string[] | 1순위 조합에 대한 사유 0~3개(화면 "왜 나에게 이 상품이 추천됐나요?"). **보조 정보** — 요청에 없는 금액이 섞인 줄은 BE·AI가 폐기한다. **모델 장애 시에는 내레이터가 규칙으로 만든 사유가 온다**(D-38). BE가 AI에 아예 닿지 못하면 빈 배열이고, 결손으로 `results`가 비어도 빈 배열 |

### 설명 — `POST /api/v1/recommendations/narrate` (D-50)

추천과 **같은 요청 본문**을 보내면 1순위 설명만 돌려준다. 프론트는 사용자가 "이 결과 설명 보기"를 펼칠 때 1회 부른다.
비회원 허용·CSRF 면제는 추천과 같다. 추천을 다시 계산하므로 본문이 다르면 다른 1순위를 설명한다 — 같은 본문을 보내라.

```json
{ "data": { "message": "…월 53,290원이에요. 지금 내시는 월 70,390원보다 월 17,100원 덜 내요.",
            "reasons": ["조건에 맞는 조합 369개 중 가장 싼 선택이에요."],
            "notices": ["청년·키즈·시니어처럼 가입 자격이 필요한 요금제 8건은 뺐어요 — …"] }, "warnings": [] }
```
내레이터 장애면 `message` 는 `null`, 배열은 빈 값이고 여전히 200 이다. 후보가 없으면 셋 다 빈 값.

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

## 회원 인증

**가입·로그인은 Google OAuth 하나뿐이다(D-34).** 자체 이메일·비밀번호 가입, 복구 코드, 계정 연결은
2026-09-17 에 제거했다. 사용자가 보는 것은 **버튼 하나**이고, Google `sub` 가 이미 있으면 로그인,
없으면 가입이다 — 서버가 알아서 나눈다.

우리는 **회원 비밀번호를 보관하지 않는다.** 따라서 비밀번호 재설정·복구 코드도 없다(잊을 것이 없다).
`localLogin` 은 항상 `false`, `googleLogin` 은 항상 `true` 다. 운영자 백오피스 로그인(`/api/v1/admin/login`)은
별개이며 비밀번호를 쓴다(D-32).

JWT 절대 수명 24시간·유휴 제한 2시간(refresh 없음, D-48). 상태를 바꾸는 요청은 모두 CSRF 토큰이 필요하다.
상세 실행법·설정은 [auth.md](auth.md), 공격 검증은 [auth-security.md](auth-security.md).

| Method | Path | 요청 | 응답 |
|---|---|---|---|
| GET | `/oauth2/authorization/google` → `/login/oauth2/code/google` | — | **유일한 가입·로그인 경로.** 완료 후 `AUTH_RETURN_URL#auth=success\|failed\|account-conflict` 로 리다이렉트 |
| GET | `/api/v1/auth/csrf` | — | `{headerName,token}` — 공개. 헤더 이름은 `X-CSRF-TOKEN` |
| POST | `/api/v1/auth/logout` | 인증+CSRF | `{loggedOut:true}` — 현재 로그인만 폐기 |
| POST | `/api/v1/auth/logout-all` | 인증+CSRF | `{loggedOut:true}` — 이 회원의 모든 로그인 폐기 |
| GET | `/api/v1/me` | 인증 | 현재 회원 |
| DELETE | `/api/v1/me` | 인증+CSRF | `{deleted:true}` — 탈퇴. 법정 보존 사본만 남는다 |
| POST | `/api/v1/me/nickname` | `{nickname}`, 인증+CSRF | 현재 회원 (D-22) |
| GET | `/api/v1/me/sessions` | 인증 | 로그인 세션 목록(`current` 플래그 포함) |
| DELETE | `/api/v1/me/sessions/{sessionId}` | 인증+CSRF | `{revoked:true}`. 남의 세션은 404 |

같은 이메일에 **다른 Google `sub`** 로 들어오면 자동 병합하지 않고 `#auth=account-conflict` 로 돌려보낸다.

**인증 에러 코드**: `YGB-AUTH-001` 401 · `YGB-AUTH-409` 409(계정 충돌) · `YGB-AUTH-DUP-NICK` 409 ·
`YGB-AUTH-403` 403(CSRF·권한) · `YGB-AUTH-404` 404(세션 없음) · `YGB-AUTH-429` 429(IP 15분 2,000회 초과 — 프록시 뒤라 전 사용자 합산, H-1) ·
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

### 4-9. 절감액 표본 — `GET /api/v1/stats/savings` (D-53, 공개)

랜딩의 "이용자들이 진단에서 확인한 절감액". **금액만** 나간다 — 계정·요금제·시각은 싣지 않는다.

```json
{ "data": { "samples": [12000, 23400, 51010], "sampleCount": 7,
            "basis": "CURRENT_PLAN", "updatedAt": "2026-09-18T02:00:00Z" }, "warnings": [] }
```

| 규칙 | 값 |
|---|---|
| 표본 단위 | **계정당 최신 저장 1건** — 한 사람이 여러 번 저장해도 한 번 |
| 기준(`basis`) | `CURRENT_PLAN` — 저장 당시 **지금 쓰는 요금제 대비** 절감액. 정가 대비가 아니다(알뜰폰은 대부분 0) |
| 제외 | `currentPlanId` 없이 저장한 건(모름 ≠ 0), 절감액 0 이하 |
| 노출 임계 | `sampleCount < 5` 면 `samples: []` 이고 **`monthlyAverage`·`monthlyMedian` 도 null**. 한두 명의 금액을 평균이라는 이름으로 내보내지 않는다 |
| 1인당 | `monthlyAverage`(랜딩의 "1인당 평균 절감액")·`monthlyMedian`. 표본과 같은 기준·같은 표본에서 낸다(D-57) |
| 개수·캐시 | 최근 30건, 60초 캐시 |

**우리가 아는 것은 "진단에서 확인한 절감액"이지 실제로 옮겼는지가 아니다.** 화면 문구도 그렇게 적는다.

### 4-10. 결손 남기기 — `POST /api/v1/catalog/gaps` (D-56, 공개)

화면 검색에서 못 찾은 것을 **수집 목록**(`catalog_candidate`)에 남긴다. 제보 게시판이 아니라 결손 보드로 간다.

```json
{ "kind": "MOBILE_PLAN", "queryText": "SKT 청년 59" }   →   { "data": { "recorded": true }, "warnings": [] }
```

| 규칙 | 값 |
|---|---|
| `kind` | `MOBILE_PLAN` · `SUBSCRIPTION_TIER`. 그 밖은 400. 통신사는 추천 경로가 자동 기록하므로 보낼 필요가 없다(굳이 보내려면 `MOBILE_PLAN` + `carrier:<이름>`) |
| `queryText` | 1~200자(앞뒤 공백 제거). 빈 값·초과는 400 |
| 응답 | **언제나 `{recorded:true}`** — 그 이름이 카탈로그에 있는지 없는지 알려주지 않는다. 기록 실패도 드러내지 않는다(fail-soft) |
| 상한 | 발신지당 15분 60회(초과 429), 표 전체 10,000행 |
| 같은 이름 | 행이 늘지 않고 `requested_cnt` 만 오른다 → 백오피스 결손 보드 상단으로 |

### 5-3b. 저장한 결과 — `/api/v1/me/saved-results` (D-51)

회원 전용·CSRF 필요. **금액은 저장 시점에 BE 가 계산기로 다시 만들어 스냅숏**으로 둔다 — 화면 숫자를 되돌려 보내지 않는다(절대 원칙 2·4).

| 메서드 | 경로 | 본문 / 응답 |
|---|---|---|
| POST | `/api/v1/me/saved-results` | 본문 = 계산기 요청 `{planId, tierIds(1개 이상), optional}` → `{ id, savedAt, cost: CostResult, monthlySavingsVsCurrent }`. 뒤 값은 `optional.currentPlanId` 가 있을 때만(없으면 `null` — 0 으로 적지 않는다, D-53). 없는 ID 는 계산기와 같은 400·404. 회원당 50개 초과는 409 |
| GET | `/api/v1/me/saved-results` | `[ { id, savedAt, cost } ]` 최신순. 스냅숏 그대로(재계산 안 함) |
| DELETE | `/api/v1/me/saved-results/{id}` | `{ deleted: true }`. 남의 것·없는 것은 404 `YGB-RES-404` |

탈퇴 시 회원 행과 함께 삭제된다(D-11 분석본 파기). 처리방침 "이용 현황" 항목에 포함.

### 5-4. 변경 시점(회수기간) — `GET /api/v1/me/switch-timing`

읽기 전용. 회원의 **현재 요금제(`currentPlanId` 파라미터 또는 5-2 저장분) + 활성 구독**으로 계산한 현재 실질월비용을 대상 요금제와 같은 조건으로 비교한다.

쿼리 파라미터:

| 이름 | 필수 | 기본 | 설명 |
|---|---|---|---|
| `targetPlanId` | ✅ | — | 비교 대상 요금제 ID |
| `switchingCost` | ✕ | 0 | 전환비용(위약금 등, 사용자 추정치) |
| `remainingContractMonths` | ✕ | 0 | 약정 잔여 개월(사용자 추정치) |
| `currentPlanId` | ✕ | — | 이번 흐름에서 고른 현재 요금제(디테일 1단계). **있으면 저장값보다 우선**하고 프로필은 바꾸지 않는다(G-35). 둘 다 없으면 400 `currentPlan` |

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

> **D-46**: 응답이 `{findings, lines, summary}` 로 바뀌었다. `lines` 는 내레이터가 만든 문구
> (`title`·`target`·`amount`·`how`)이고 `findings` 는 기존 원본이다. 내레이터가 닿지 않으면
> `lines` 는 규칙 코드와 금액만 담고 `how` 는 빈 문자열이다 — 화면이 통째로 비지 않는다.

요청 시 현재 구독·요금제 기준으로 **재탐지해 저장·반환**한다. 응답 200 `data`는 탐지 결과 배열:

```json
{ "data": [
  { "rule": "BENEFIT_OVERLAP", "targetRef": "넷플릭스", "wastedAmount": 13500 }
] }
```

`rule` = `BENEFIT_OVERLAP`·`TIER_DUPLICATE`·`BUNDLE_OVERLAP`, `wastedAmount`는 월 단위 낭비 금액(원).


각 항목은 `{ rule, targetRef, wastedAmount, provenance }` 다.

| provenance | 뜻 |
|---|---|
| `DERIVED` | 카탈로그 정가·혜택으로 계산한 금액 |
| `ESTIMATED` | 요금제가 **등급을 밝히지 않아** 상한(사용자가 내는 금액 전액)으로 잡은 값. **표시 전용**(D-17) |

`ESTIMATED` 는 "확실히 이만큼 버린다" 가 아니라 "확인해 보세요" 다. 화면은 둘을 같은 말로 적으면 안 된다.
`BENEFIT_OVERLAP` 은 2026-09-17 부터 `FREE` 뿐 아니라 **할인·요금제 포함 혜택도** 본다 —
그전에는 운영 혜택의 70%(`BUNDLE_INCLUDED`)를 놓쳤다. 골든 케이스 G-09 e·f.

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
  { "item": "ESSENTIAL", "policyVersion": "2026-09-17", "agreed": true, "current": true, "agreedAt": "2026-09-17T...Z", "withdrawnAt": null },
  { "item": "MARKETING", "policyVersion": "2026-09-15", "agreed": true, "current": false, "agreedAt": "...", "withdrawnAt": null }
] }
```

`ESSENTIAL`(필수)은 가입 시 기록되며 **철회 불가**(계약 이행 근거). `MARKETING`(선택)만 6-3 으로 변경한다.

`current` 는 이 기록이 **지금 처리방침 버전**에 대한 것인지다. 하나라도 `false` 면 화면이 다시 물어야 한다.
`MARKETING` 이 `agreed:true, current:false` 면 **구버전 동의이며 유효하지 않다** — 발송 전에 다시 받아야 한다.

### 6-2-1. 변경 고지 확인 — `POST /api/v1/me/consent/acknowledge` (인증+CSRF)

본문 없음 → 200 `{ "data": { "acknowledgedVersion": "2026-09-17" } }`.

**필수(ESSENTIAL) 항목만** 현재 버전으로 올린다. 마케팅 동의는 승계하지 않는다 —
"방침을 읽었다"가 "광고를 받겠다"를 뜻하지 않는다. 다시 받으려면 6-3 을 쓴다.
필수는 계약 이행 근거라 확인 전에도 서비스를 막지 않는다.

### 6-3. 마케팅 동의 변경 — `POST /api/v1/me/consent/marketing` (인증+CSRF)

요청 `{ "agree": true }` → 응답 200 `{ "data": { "agreed": true } }`. `agree`가 boolean이 아니면 400 `YGB-REQ-001`.

> **회원 탈퇴·데이터 삭제**는 `DELETE /api/v1/me`(회원 인증, [auth.md](auth.md)). 법정 보존 사본만 별도 보존.

## 7. 운영자 API (`/api/v1/admin/**`)

백오피스 전용이다. `POST /api/v1/admin/login` 만 공개이며 나머지는 운영자 세션이 필요하다.
프론트에서는 nginx 가 `/admin.(html|js)` 만 BE 로 프록시한다 — 일반 회원 화면에는 노출되지 않는다.

> **편집에는 `yogobi.catalog.combined-csv`(원본 파일 경로)가 필요하다.** 비어 있으면 **조회는 되지만**
> 제안·승인은 **503 `YGB-CAT-503`** 으로 거부된다. 승인은 제안을 FAILED 로 닫기 **전에** 막으므로,
> 경로를 채운 뒤 같은 제안을 그대로 승인할 수 있다.
> **운영에는 일부러 설정하지 않는다(D-37).** 레포 CSV 가 원본이고 카탈로그 변경은 CSV 를 고쳐 배포한다 —
> 따라서 이 편집 API 는 **운영에서 항상 503** 이고, 로컬·테스트에서만 동작한다.

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/v1/admin/login` | 운영자 로그인 `{id,password}`. 실패는 401 하나로만 답한다 (D-32) |
| GET | `/api/v1/admin/session` | 관리자 여부 확인 |
| GET | `/api/v1/admin/dashboard` | 사용 지표. 블록 다섯 — `health`(내레이터 실패 종류별·지연·마지막 성공) · `quality`(카탈로그 품질 7종, 아래) · `funnel`(D-36 게이트 퍼널 14일, **사람 수와 횟수를 나눠 센다**) · `savings`(찾아 준 절감액, D-54·D-59) · 통계 |
| POST | `/api/v1/admin/harvest/run` | 일일 수집 즉시 실행 (정기: 매일 09:00 KST) |

**`quality` 블록 7종** — 각 항목은 `{count, sample[]}` 이다.

| 키 | 무엇을 잡나 |
|---|---|
| `carrierNameVariants` | 같은 통신사가 표기만 다르게 두 줄 |
| `duplicateTierNames` · `samePriceTiers` | 한 서비스 안의 중복 등급 |
| `placeholderPlans` · `plansWithoutSource` | 출처가 URL 이 아니거나 가격이 0 인 행 |
| `mnoNetworkGaps` | MNO 의 망별 후보가 3건 미만("0건"만 보면 "1건뿐"이 안 보인다) |
| `nonMonthlyTiers` | **월 단가가 아닌 구독 등급**(G-54). 신호는 둘 — 등급 **이름**의 기간 표기, 같은 서비스 중앙값의 10배 초과. **비고는 보지 않는다**(우리가 쓴 설명문이라 기간 낱말이 섞인다) |
| POST | `/api/v1/admin/smartchoice/sweep` | 스마트초이스 스냅샷 스윕 즉시 실행 |
| POST | `/api/v1/admin/fx/refresh` | 환율 즉시 갱신(정기: 09:15 KST). 실패해도 이전 값 유지 — 응답 `updated` 로 구분 |
| GET | `/api/v1/admin/reports` | **제보 게시판** — `catalog_report`+`service_report` 합본 최신순. `?status=PENDING` · `?limit=`(기본 50·최대 200). **제보자 회원 신원은 싣지 않는다**(D-42) |
| PATCH | `/api/v1/admin/reports/{kind}/{id}` | 처리 상태 변경(`PENDING`·`RESOLVED`·`REJECTED`). `kind` 는 `CATALOG`·`SERVICE`. **원본 카탈로그는 바뀌지 않는다** — 가격 수정은 D-28 승인을 따로 탄다 |
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


## 백오피스 고도화 (D-52, 2026-09-18) — 전부 `/api/v1/admin/**` ADMIN 전용

| 메서드 | 경로 | 내용 |
|---|---|---|
| GET | `/admin/gaps?status=&limit=` | 결손 목록. 상태 없으면 할 일(REQUESTED·IN_PROGRESS)만, `requestedCount` 내림차순. 행: `{id, kind, queryText, status, requestedCount, lastRequestedAt, note, updatedAt}` |
| PATCH | `/admin/gaps/{id}` | `{status: REQUESTED\|IN_PROGRESS\|PENDING\|VERIFIED\|REJECTED, note?}` — `note` 없으면 그대로, 빈 문자열이면 지움 |
| PATCH | `/admin/reports/{kind}/{id}` | 기존 + `note?`(1,000자). 목록 행에 `note`·`updatedAt`·`targetId` 추가 |
| GET | `/admin/audit?limit=` | `[{at, actor(이메일), action, target, detail}]` 최신순. action 은 `CATALOG_CREATE/UPDATE/DELETE`·`REPORT_<상태>`·`GAP_<상태>`·`JOB_HARVEST/SMARTCHOICE/FX/PURGE` |
| GET | `/admin/dashboard` | 아래 블록이 추가됨 |

`dashboard` 추가 블록:

```jsonc
"health": { "narrationFailures": 0, "narrationFailuresByKind": {"contract":0,"connect":0,"http":0,"token":0,"response":0},
            "narrationCalls": 12, "narrationAvgMs": 380, "narrationMaxMs": 3800, "narratorLastOkAt": "…",
            "recommendationsToday": 41, "reportShownToday": 41, "reportViewersToday": 9 },   // 횟수 ≫ 사람이면 반복 호출 의심
"quality": { "carrierNameVariants": {"count":0,"sample":[]}, "duplicateTierNames": {…}, "samePriceTiers": {…},
             "placeholderPlans": {…}, "plansWithoutSource": {…}, "mnoNetworkGaps": {"count":1,"sample":["SKT · LTE 1건"]} },   // 3건 미만이면 얇다고 센다 — 0 과 1 은 사용자에게 같다
"savings": { "basis": "CURRENT_PLAN", "members": 12, "improved": 10,     // D-54. 진단 기준이다 — 실제 이전 여부는 모른다
             "monthlyTotal": 256080, "monthlyMedian": 17100, "monthlyAverage": 21340, "monthlyMax": 51010,
             "annualTotalEstimate": 3072960,                            // 월 × 12. 1년치 실측이 아니다
             "histogram": [{"bucket":"1~3만","count":5}], "daily": [{"date":"2026-09-18","savedCount":3,"monthlySum":41000}] },
"stats":   { "windowDays": 7, "topRecommended": [{carrier, plan, count}], "dataGbHistogram": [{dataGb, count}],
             "topSaved": [{carrier, plan, count}], "savedTotal": 3 },
"funnel":  { …기존…, "unique": {gateShown, memberLogin, reportShown, calendarShown, resultSaved},
             "uniqueDaily": [{date, …}], "contaminatedUntil": "2026-09-18" }   // 횟수 열은 그날까지 무한 호출로 부풀어 있음
```
카운터·타이머(`narration.*`)는 프로세스 수명이다 — 재시작하면 0. 나머지는 DB.
