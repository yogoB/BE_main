# 제품 목표 반영 — 구현 전 계약안 (승인 대기)

작성일 2026-09-11 · 기준 HEAD `43aed4c` (최신 코드 재확인 완료) · 상태 **제안(미승인·미구현)**

출처: `AI-/docs/backend-product-review-and-prompt.md`의 "백엔드 담당자에게 전달할 프롬프트".
이 문서는 **구현 전 산출물**이다. 공개 API 계약(`architecture.md §3`)·DB·AI 계약은 §6의 승인 항목이
승인된 뒤에만 변경한다. 이 문서 작성으로 기존 코드·계약을 수정하지 않았다.

---

## 0. 최신 코드 재확인 결과 (요구사항 × 상태)

검토 문서의 판정을 HEAD `43aed4c` 실제 소스로 재확인했다. 결과는 검토와 일치한다.

| 제품 목표 | 상태 | 소스 근거 (재확인) |
|---|---|---|
| 동일 엔진 공유(필터·챗봇) | **구현** | `RecommendationService.recommend/calculate` 공유, 금액은 `CostCalculator`에 위임 |
| 현재 지출 대비 절감 | **미구현** | `CostCalculator:56-58` `baseline`=후보 기본료+원하는 티어 정가, `monthlySavings`=baseline−실질비용. 현재 청구액·개인 결제 미연결 |
| 사용자별 지출 관리 API | **미구현(기반만)** | `V2`에 `app_user.current_plan_id`·`user_subscription`·`payment_record`·`DetectionService` 존재. **`/me/subscriptions·payments·detections` 컨트롤러 없음** (`grep @*Mapping` 확인) |
| 유지 조건 보존 | **부분** | 입력은 GB·서비스ID·`currentCarrier·networkType·contractType·hasFamilyBundle`뿐(`RecommendationRequest`). 후보 필터는 데이터량·망만(`CatalogReader.findCandidatePlans`). 통신사 유지·정확한 등급·광고 여부 없음 |
| 구독 등급 보존 | **미구현** | 추천은 `findRepresentativeTiers`로 대표티어 자동선택(`RecommendationService:46`). 사용자 지정 등급 보존 추천 아님. `/calculator`만 `tierIds` 직접 수용 |
| 최저가 보장 | **제한적** | 번들 first-fit 순서 의존(`CostCalculator:78-94`). 합성 반례 재현: 통신10000·A/B/C 각10000·AB번들15000·BC번들1000 → 순서 따라 35000/21000 |
| 시간·전환 비용 | **미구현** | `annualSavings=monthlySavings*12`. 전환비용·기간별 가격·회수기간 계산 없음. §8·G-11 **스펙만 존재** |
| 할인 적용 조건 | **부분/정책 필요** | `hasFamilyBundle`은 `Accuracy`에만 영향, 결합할인 규칙(priority 300)·프로모션 규칙(400) **자체가 없음**(`CostCalculator.TELECOM_RULES`엔 약정·선택약정 둘뿐). 요금제 고유 약정할인은 `contractType=NONE`에도 적용 |
| 혜택 유효기간·가입 조건 | **저장≠적용** | `valid_from·valid_to·age_limit`은 `V1` DDL·`CatalogSeedLoader`에만. `CatalogReader` 후보/혜택 선택에서 **미참조** |
| 상품 수집·변경 이력 | **미구현** | 실행 시 CSV 업서트(혜택은 삭제 후 재적재). 변경 버전·변경 이벤트·주기 수집 없음 |
| 근거 제시 | **부분** | `BreakdownLine{label,amount,provenance,note}`. 원문 URL·수집일·적용기간·데이터 버전은 응답에 없음 |
| 변경 알림 | **미구현** | `alert` 패키지·`@Scheduled`·`alert_schedule`·`promotion`·`contract` 테이블 **전부 없음**(마이그레이션 V1~V4 확인). 인증 메일은 별개 |
| 대화로 조건 수정 | **미구현** | `ChatController` 단일 발화만. 사용자별 조건 저장·병합 없음 |

> 문서 드리프트도 함께 잡을 것: `architecture.md`가 `contract·promotion·alert_schedule`을 "스키마만 선반영"으로
> 적지만 실제 마이그레이션에 없다. §3 API 표의 `/me/*`도 미구현이다. 승인 후 문서를 실제와 맞춘다.

**핵심 발견:** 제품 목표의 상당 부분은 새로 설계할 게 아니라 **이미 스펙만 있는 미구현분**이다 —
`domain.md §8`(전환비용·`월절감액 = 현재 실질월비용 − 추천 실질월비용`·회수개월), `§9`(현재통신사·약정·가족결합·현재구독 입력),
`testing.md G-11`(회수기간). 계약안은 이 기존 스펙·용어를 재사용하며 새 용어는 §5에 표시한다.

---

## 1. 요청/응답 계약안

원칙: **기존 필드 의미 불변**(정가 대비 `baseline`/`monthlySavings` 유지), 현재 지출 비교는 **추가 필드**로 분리.
미입력은 절대 `false`/`0`/기본티어로 추측하지 않는다(3-상태: 값/`null`). 회원 개인 데이터는 인증 사용자에 귀속, 비회원 일회성 비교는 유지.

### 1-A. 추천 요청 확장 — 유지 조건 + 현재 지출 (추가 필드, 하위호환)

기존 `POST /api/v1/recommendations`에 optional 블록만 추가. 기존 요청은 그대로 동작.

```jsonc
// POST /api/v1/recommendations  (확장 제안 — 굵은 부분이 신규)
{
  "required": { "monthlyDataGb": 20, "wantedServiceIds": [1, 3] },
  "optional": {
    "currentCarrier": "SKT", "networkType": "5G",
    "contractType": "SELECTIVE_25", "hasFamilyBundle": true,

    "comparisonMonths": 12,                 // 비교 기간(§4-1). 미지정 시 서버 기본값
    "constraints": {                        // 유지 조건. 각 필드 null=미지정(추측 금지)
      "keepCarrier": true,                  // 현재 통신사 유지(=currentCarrier와 의미 다름)
      "requiredTierIds": [3],               // 특정 구독 등급 보존(대표티어 자동선택 대신)
      "allowAdTier": false                  // 광고형 등급 허용 여부
    },
    "currentSpend": {                       // 비회원의 일회성 현재 지출(회원은 저장분 사용)
      "currentPlanId": 42,                  // 또는 monthlyAmount 직접
      "currentTierIds": [2],
      "monthlyAmount": null,                // USER_PROVIDED 총액(있으면 우선, provenance 표기)
      "deviceInstallment": { "monthlyAmount": 15000, "remainingMonths": 8 },
      "contract": { "type": "SELECTIVE_25", "remainingMonths": 6 }
    }
  }
}
```

### 1-B. 추천 응답 확장 — 현재 지출 비교 블록 (추가)

```jsonc
// 200 (확장 — accuracy·missingInputs·results[].{planId..breakdown}는 기존과 동일)
{
  "accuracy": "PARTIAL",
  "missingInputs": [ { "field": "hasFamilyBundle", "impact": "...", "howToFind": "..." } ],
  "comparison": {                           // 신규. currentSpend 없으면 이 블록 없음/null
    "currentMonthlyCost": 78500,            // 현재 실질월비용(동일 엔진 계산 or USER_PROVIDED)
    "currentProvenance": "DERIVED",         // 저장분 계산=DERIVED, 총액직접입력=USER_PROVIDED
    "comparisonMonths": 12,
    "dataScope": { "collectedAt": "2026-09-10", "dataVersion": "seed-2026-09-10" }
  },
  "results": [{
    "planId": 42, "planName": "5G 슬림+", "carrier": "SKT",
    "monthlyTotal": 71300, "baseline": 89000,          // ← 의미 불변(정가 대비)
    "monthlySavings": 17700, "annualSavings": 212400,  // ← 의미 불변
    "vsCurrent": {                          // 신규. currentSpend 있을 때만
      "monthlySavings": 7200,               // currentMonthlyCost − monthlyTotal
      "periodSavings": 86400,               // 비교기간 총액 차 − 전환비용
      "switchingCost": 60000,               // §4-2 범위. earlyTerminationFee+재약정손실
      "switchingCostProvenance": "ESTIMATED",
      "paybackMonths": 9                    // ceil(switchingCost / monthlySavings), §8/G-11
    },
    "constraintReport": {                   // 유지 조건 판정
      "satisfies": true,
      "relaxed": []                         // 조건 완화 대안이면 위반 조건 나열(§1-D)
    },
    "breakdown": [
      { "label": "5G 슬림+ 기본료", "amount": 55000, "provenance": "OFFICIAL",
        "sourceUrl": "https://...", "collectedAt": "2026-09-10" },   // 신규 근거 필드(선택)
      { "label": "선택약정 25% 할인", "amount": -13750, "provenance": "DERIVED" },
      { "label": "넷플릭스 스탠다드", "amount": 13500, "provenance": "OFFICIAL",
        "note": "제휴 혜택 적용", "validTo": "2026-12-31" }
    ]
  }]
}
```

### 1-C. 현재 지출 없음 / 정보 부족 응답

- `currentSpend` 미제공 → `comparison`·`results[].vsCurrent`를 **생략(null)**, `missingInputs`에
  `currentSpend` 안내 추가. **정가 대비 `monthlySavings`와 혼동 금지**(별도 필드 유지). 오류 아님(200).
- 필수 누락(기존 유지): `400 YGB-REQ-001`(`monthlyDataGb`/`wantedServiceIds`).
- 후보 없음(기존 유지): `422 YGB-CAL-001` 유형. 유지 조건으로 후보가 0이 되면 §1-D의 완화 대안만 반환하거나
  `missingInputs`로 완화 가능성을 안내(정책 §4).

### 1-D. 유지 조건 위반 처리

- **필수 유지 조건 위반 후보는 기본 추천에서 제외**. 조건을 완화한 더 싼 대안은 `results`에 섞지 않고
  별도 `relaxedAlternatives`(또는 `constraintReport.relaxed`)로 표시하고 **명시적 선택을 받는다**.
- `keepCarrier=true`면 `currentCarrier`와 다른 통신사 후보 제외. `keepCarrier=null`이면 제약 없음(추측 금지).
- `requiredTierIds` 지정 시 해당 서비스는 대표티어 자동선택 대신 지정 등급 사용.

### 1-E. 회원 개인 데이터 API (신규, 인증 필요 — ROLE_MEMBER)

기존 `SecurityConfig`의 `/me/**` 보호를 재사용. 저장은 인증 사용자에 귀속.

```
GET/POST/DELETE /api/v1/me/subscriptions        # user_subscription CRUD
POST            /api/v1/me/current-plan          # current_plan_id 설정
POST            /api/v1/me/payments/import       # PaymentHistoryProvider(Mock→EML→수기)
GET             /api/v1/me/detections            # DetectionService 조회(HTTP 노출)
PUT             /api/v1/me/conditions            # 저장 유지조건/비교기간/현재지출 프로필
GET             /api/v1/me/switch-timing         # §8 회수기간(저장분 기반)
GET/PUT/DELETE  /api/v1/me/alerts                # 알림 구독 설정
```

### 1-F. 알림 설정 요청/응답 예시

```jsonc
// PUT /api/v1/me/alerts
{ "active": true,
  "scope": { "priceIncrease": true, "benefitChange": true,
             "betterAlternative": true, "promotionEnding": true, "contractExpiry": true },
  "minMonthlySavings": 3000,          // 최소 절감 임계값(기본값은 §4-5 정책)
  "channel": "EMAIL" }                // EMAIL | NONE (PUSH 추후, §4-5)
// 200: { "data": { ...위 설정 반영값, "updatedAt": "..." } }
```

---

## 2. DB 변경안

기존 CSV 적재·트랜잭션·검증(`CatalogSeedLoader`)과 V2 개인 테이블을 **재사용**한다. 아래는 신규/보강만.

```sql
-- V5: 유지조건 프로필 + 현재지출 보강 (개인, 인증 사용자 귀속)
CREATE TABLE user_condition (
    user_id            BIGINT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    keep_carrier       BOOLEAN,                 -- null=미지정(추측 금지)
    comparison_months  INTEGER,                 -- null=서버 기본값
    allow_ad_tier      BOOLEAN,
    required_tier_ids  BIGINT[],
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE user_subscription ADD COLUMN is_active_billing BOOLEAN NOT NULL DEFAULT TRUE;
-- 단말기 할부: 현재지출/전환비용 이중계산 방지를 위해 별도 보관(§4-2)
CREATE TABLE user_device_installment (
    user_id          BIGINT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    monthly_amount   BIGINT NOT NULL,
    remaining_months INTEGER NOT NULL
);

-- V6: 상품 변경 이력(근거·재계산 트리거) — 재적재 무변경은 이벤트로 남기지 않음
CREATE TABLE product_change_event (
    id            BIGSERIAL PRIMARY KEY,
    entity_type   VARCHAR(20) NOT NULL,   -- MOBILE_PLAN|SUBSCRIPTION_TIER|PLAN_BENEFIT|BUNDLE
    entity_ref    BIGINT NOT NULL,
    change_type   VARCHAR(20) NOT NULL,   -- PRICE|BENEFIT|TIER|STATUS|VALIDITY
    before_json   JSONB, after_json JSONB,
    effective_at  DATE,
    data_version  TEXT NOT NULL,          -- 비교에 사용한 스냅샷 버전
    source_url    TEXT, detected_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pce_entity ON product_change_event(entity_type, entity_ref);

-- V7: 알림 구독 + 발송 아웃박스(재시도·중복방지·재시작 유실방지)
CREATE TABLE alert_subscription (
    user_id             BIGINT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    scope               JSONB NOT NULL,
    min_monthly_savings BIGINT NOT NULL DEFAULT 0,
    channel             VARCHAR(10) NOT NULL DEFAULT 'EMAIL',
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE notification_outbox (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    dedup_key     TEXT NOT NULL UNIQUE,   -- (user, event, data_version) → 동일변경 재처리 방지
    payload_json  JSONB NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'PENDING',  -- PENDING|SENT|FAILED
    attempts      INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at       TIMESTAMPTZ
);
```

`valid_from·valid_to·age_limit`은 **신규 컬럼 아님** — 이미 있는 값을 후보/혜택 선택에서 **사용**하도록 로직만 바꾼다.
`contract·promotion·alert_schedule`(architecture 문서상 Phase 2)은 실제로 없으므로, 필요한 최소 형태를 위 표로 대체 제안한다.

---

## 3. 기존 API·AI 계약 영향 / 호환성

| 대상 | 변경 | 호환성 |
|---|---|---|
| `POST /recommendations` | optional에 `comparisonMonths·constraints·currentSpend` 추가, 응답에 `comparison·vsCurrent·constraintReport` 추가 | **하위호환**(모두 선택/추가). 기존 클라이언트 무영향 |
| `POST /calculator` | 변경 없음(이미 `tierIds` 직접 수용) | 무영향 |
| `/catalog/*`·인증·챗봇 | 변경 없음 | 무영향. 기존 102 테스트 회귀 유지 |
| `breakdown[]` 근거 필드 | `sourceUrl·collectedAt·validTo` 추가(선택) | 추가 필드, 무영향 |
| **AI `/narrate`** | AI는 `extra=forbid`. **확장 `CostResult` 전체를 그대로 보내지 않는다.** BE가 기존 `/narrate` 계약 필드만 매핑해 전달하거나, 합의된 계약 확장 후 `AI-/docs/contract.md` 사본과 양쪽 테스트를 함께 맞춘다 | 계약 변경 시 §6 승인 + 같은 날 사본 동기화(D-06) |
| AI `/parse` | 유지 조건·현재지출은 확인된 값만 BE가 채운다. 회원 토큰·개인 DB는 AI에 전달 안 함 | 무영향 |

마이그레이션: V5~V7 추가만(기존 V1~V4 불변). 롤백 없이 순방향. AI 서버 장애에도 필터 기반 비교는 동작.

---

## 4. 미확정 정책 — 제안 + 확정 필요

| # | 정책 | 제안(미확정) | 확정 방법 |
|---|---|---|---|
| 1 | **비교 기간** | 기본 12개월. `comparisonMonths`로 24 등 선택. `annualSavings`(=*12)는 의미 불변 유지 | 기본값 사람 확정 |
| 2 | **전환비용 범위** | `switchingCost = 할인반환금 + 재약정손실`. **잔여 할부는 현재·후보 양쪽에 그대로 유지되므로 월비교에서 상쇄, switchingCost에 미포함**(이중계산 방지). 미확정 비용은 0원으로 두지 않고 `ESTIMATED`/확인불가 표기 | 사람 확정 + 골든 케이스 |
| 3 | **할인 적용 대상** | 요금제 고유 약정할인을 `contractType=NONE`에도 적용하는 **현재 동작은 정책상 모호**(약정 가입 전제). 제안: 약정 전제 할인은 해당 약정 명시 시에만 적용. 가족결합은 `BundleDiscountRule`(priority 300) 신규 + 결합할인액 데이터 필요 | **골든 케이스로 확정 후 규칙 구현**(testing.md 선반영) |
| 4 | **수집 범위·주기** | 요청 중 크롤링 금지(D-05). 검증된 시드 스냅샷 갱신부터. 알뜰폰 기간 프로모션은 별도 배치(이전 논의: 하루 3회) — **D-05 '시드 고정'과 상충하므로 범위 재확정 필요** | 사람 확정(스코프·주기) |
| 5 | **알림 채널·임계값** | 채널 EMAIL(인증메일과 별도 발신 경로)·NONE 우선, PUSH 추후. 최소 절감 임계값 기본 0원(사용자 설정 우선) | 사람 확정 |

`comparisonMonths` 미지정·소수 GB·구독 없음 등 경계 동작은 계약에 명시하고 임의 보정하지 않는다.

---

## 5. 단계별 구현 순서 + 인수 테스트

**원칙:** 기존 미커밋 변경·완료된 인증·기초 계산기 재작성 금지. 새 계산 규칙은 `testing.md` 골든부터.
지출/조건 계약 → 정확한 비용 비교 → 데이터 갱신/근거 → 알림 → 대화 편의 순, 작은 단위.

| 단계 | 범위 | 신규 골든 | 인수 테스트(발췌) |
|---|---|---|---|
| **P0** | 본 계약안 §6 승인 | — | 공개 §3 표 변경은 승인 후 |
| **P1** | 현재지출·유지조건 계약: request 확장 + `comparison`/`vsCurrent` + `/me/subscriptions·current-plan·conditions`(인증 재사용) | G-14 현재지출 delta | 현재지출 없으면 `vsCurrent` 미생성·정가 할인액과 혼동 안 함 / 통신사유지·등급 위반 후보는 기본 추천 제외 / 미입력을 false·0·기본티어로 추측 안 함 / 다른 회원 데이터 접근 불가 |
| **P2** | 정확한 최저가: 겹치는 번들 **정확 열거**로 최소 보장 + `valid_from/to·age_limit` 후보/혜택 필터 | G-12 번들순서(35000/21000→**21000**), G-13 만료·미시작·가입불가 혜택 미사용 | 번들 순서 바꿔도 결과 동일 / 만료·미시작 혜택이 비용을 낮추지 않음 / 첫 달만 싼 상품이 기간총액 최저가로 오인 안 됨 |
| **P3** | 데이터 갱신·근거: `product_change_event` + 시드 로더가 **의미있는 변경만** 이벤트화(동일 재적재≠변경, 일시누락≠단종) + breakdown 근거(URL·수집일·적용기간·데이터버전) | — | 동일 자료 재적재는 무이벤트 / 신규가·기존가 구분 / 근거 추적 가능 |
| **P4** | 변경시점·알림: §8 회수기간(G-11 구현) + `alert_subscription`·`product_change_event`→공통 엔진 재계산 + `notification_outbox`(재시도·중복방지·재시작 유실방지). **채널 발송은 스텁** | G-11 구현 | 현상품 인상/조건맞는 신규/할인종료가 각각 재계산·알림 / 해제·무관사용자·신규가입자전용 변경엔 미발송 / 동일변경 재처리·실패재시도·재시작에도 유실·중복 없음 / **채널 스텁은 완료로 보고 안 함** |
| **P5** | 대화 편의: 사용자별 조건 저장 + 추가 발화 병합(대화에서 바꾼 조건만 반영, 나머지 BE 보존) | — | 필터·챗봇·알림 재계산이 같은 규칙·금액·정렬 |

**전 단계 공통 회귀:** 기존 계산 골든(G-01~G-10)·필터·챗봇·회원 인증 102개 유지. 금액은 `long`/`Money`·순수 `pricing` 유지.

---

## 6. 승인 필요 항목 (공개 API 계약 — `AGENTS.md` 규칙)

아래는 `architecture.md §3` 표를 사람이 변경해야 하는 항목이다. 승인 전 구현하지 않는다.

1. `POST /recommendations` 요청/응답에 `comparisonMonths·constraints·currentSpend` / `comparison·vsCurrent·constraintReport` 추가.
2. 신규 회원 엔드포인트 확정: `/me/subscriptions`, `/me/current-plan`, `/me/payments/import`, `/me/detections`, `/me/conditions`, `/me/switch-timing`, `/me/alerts`.
3. `breakdown[]` 근거 필드(`sourceUrl·collectedAt·validTo`) 추가.
4. AI `/narrate` 계약: 확장 `CostResult` 매핑 방식(호환 매핑 vs 계약 확장) 확정 → `AI-/docs/contract.md` 사본·양쪽 테스트 동기화(D-06).
5. §4의 미확정 정책 5건 확정(특히 2·3·4는 골든 케이스로 고정).

승인되면: `testing.md`에 골든(G-12~G-14) 선작성 → `architecture.md §3`·§4·`domain.md §2`(신규 용어 `currentMonthlyCost·comparisonMonths·constraints` 등) 갱신 → 단계별 구현 → `state.md`/`worklog.md`·계약 사본·지식 그래프 동기화.
