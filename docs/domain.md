# 도메인

계산 로직을 건드리거나 이름을 지을 때 읽는다. 여기 없는 규칙을 추측해서 만들지 않는다.

---

## 1. 핵심 정책 — "미사용 혜택은 0원"

혜택의 **가치**를 0으로 평가한다는 뜻이다. 총액에서 빼는 것이 아니다.

- ❌ "안 쓰는 구독료를 총비용에서 제외한다"
- ✅ "넷플릭스를 안 보는 사용자에게, 넷플릭스 무료 제공을 이유로 비싼 요금제를 추천하지 않는다"

요고비가 통신사 추천과 다른 유일한 지점이다. 이게 깨지면 서비스가 존재할 이유가 없다.

### 수식

```
실질월비용(요금제 P, 원하는 서비스 집합 S)
  = P.기본료
  - 약정할인(P) - 선택약정할인(P) - 결합할인(P) - 프로모션할인(P)
  + Σ_{s ∈ S} 최저경로비용(s, P)

최저경로비용(s, P) = min(
    s.정가,
    s.정가 - benefit(P, s).할인액,
    s를 포함하는 번들의 분담가
)

※ s ∉ S 인 서비스에 대한 P의 제휴 혜택은 계산에 일절 반영하지 않는다.
```

`min()`이 있는 이유: 같은 서비스를 제휴·번들·부가서비스 여러 경로로 받을 수 있고,
**서비스별로 가장 싼 경로 하나**만 선택해야 한다.

---

## 2. 용어 ↔ 코드 식별자

**클래스·컬럼·필드 이름은 이 표에서 찾는다.** 없으면 추가하고 커밋한다.

### ⚠️ 최우선 주의 — "요금제" 충돌

| 한국어 | 대상 | 식별자 | **금지** |
|---|---|---|---|
| 요금제 | 통신 요금제 (5G 슬림+) | `MobilePlan` / `mobile_plan` | `Plan` `Tariff` `PricePlan` |
| 요금제 | OTT 요금제 (넷플 스탠다드) | `SubscriptionTier` / `subscription_tier` | `Plan` `OttPlan` `Grade` |

**`Plan` 단독 식별자는 변수명에도 쓰지 않는다.** `mobilePlan` / `tier`로 쓴다.

### 엔티티

| 한국어 | 식별자 | 비고 |
|---|---|---|
| 통신사 | `Carrier` | |
| 통신 요금제 | `MobilePlan` | |
| 구독 서비스 | `SubscriptionService` | 음악 포함하므로 `OttService` 금지 |
| 구독 티어 | `SubscriptionTier` | |
| 제휴 혜택 | `PlanBenefit` | `PartnerBenefit` `Perk` 금지 |
| 번들 상품 | `BundleProduct` | `Package` `Combo` 금지 |
| 내 구독 | `UserSubscription` | |
| 결제 기록 | `PaymentRecord` | |
| 탐지 결과 | `DetectionResult` | |
| 가맹점 별칭 | `MerchantAlias` | |
| 약정 / 프로모션 | `Contract` / `Promotion` | Phase 2 |

### 초기 데이터 적재

| 한국어 | 식별자 | 비고 |
|---|---|---|
| 카탈로그 시드 로더 | `CatalogSeedLoader` | `catalog` 내부, CSV 스냅샷 적재 |
| AI 서버 게이트웨이 | `AiGateway` | `/parse`·`/narrate` HTTP 호출과 응답 검증 |
| 서버 간 내부 토큰 | `AI_INTERNAL_TOKEN` | BE와 AI만 공유. 사용자 인증 토큰과 별도이며 프론트에 노출하지 않음 |
| 챗봇 요청 진입점 | `ChatController` | 기존 추천 서비스 재사용, 추가 입력·필터 폴백 안내 |
| 챗봇 응답 | `ChatResponse` | `status`, `message`, `recommendation` |
| 통신 요금제 로더 | `loadMobilePlans` | 통신사 이름 파생·망 매핑·(carrier_id,name) 업서트, 파일 있을 때만 |
| 제휴 혜택 로더 | `loadPlanBenefits` | 요금제 자연키(carrier,plan_name) 해석, 요금제별 혜택 교체(멱등), 미매칭 시 전체 실패 |
| 통신사 종류 파생 | `carrier_type` | SKT/KT/LGU+ → `MNO`, 그 외 → `MVNO` (템플릿에 없어 이름으로 파생) |
| 카탈로그 읽기 계층 | `CatalogReader` | `catalog` 내부, DB 행 → pricing 도메인 매핑 (읽기 전용) |
| 개발용 더미 시드 로더 | `DevSeedLoader` | dev 프로파일 전용, `db/seed/dev/` 더미 적재 (로컬 bootRun) |
| 중복 결제 탐지기 | `DuplicateDetector` | `detection` 순수 도메인, 세 규칙 독립 적용 |
| 탐지 응용 서비스 | `DetectionService` | 활성 구독·현재 요금제 혜택·번들 로드 → 탐지 → 저장 |
| 사용자(예약어 회피) | `app_user` | `user` 는 PostgreSQL 예약어. `current_plan_id` 로 현재 요금제 |
| 활성 구독 | `ActiveSubscription` | 탐지 입력 — 현재 결제 중인 구독(서비스·티어·월액) |
| 탐지 결과 | `DetectionFinding` | 규칙·대상·월 낭비액 (DB `DetectionResult`와 구분되는 도메인 값) |
| 가맹점 정규화기 | `MerchantNormalizer` | `subscription` 순수 도메인, 가맹점 원문 → service_id (모르면 empty) |
| 회원 인증 | `AuthService` / `AuthController` | 자체 가입·로그인, 회원 정보와 명시적 계정 연결 |
| 회원 토큰 | `AuthTokens` / `auth_session` | JWT + 브라우저 확인 쿠키. DB에는 SHA-256 지문만 저장 |
| Google 로그인 | `GoogleLogin` / `google_sub` | 검증한 OIDC sub로 식별. 이메일 자동 병합 금지 |
| 인증 접근 제어 | `SecurityConfig` | 비회원 API 공개, 회원 API는 현재 사용자만 |
| 인증 시도 제한 | `AuthRateLimit` / `auth_rate_limit` | IP·정규화 이메일·재인증 사용자별 15분 제한 |
| 별칭 매칭 방식 | `MatchType` | `CONTAINS` `PREFIX` (common enum) |
| 대표 티어 | `findRepresentativeTiers` | 서비스 → 티어 선정: 스탠다드(광고 제외) → 광고 제외 최저가 → 최저가 |
| 번들 구성 | `bundle_item` / `tier_ids` | DB 관계 테이블 / 시드 CSV의 티어 ID 목록 |
| 티어 비고 | `note` | 시드 원문 보존 |
| 번들 제공자 | `provider` | 시드 원문 보존 |
| 연령 제한 | `age_limit` | 통신 요금제 수집값 |
| 출처 URL / 수집일 | `source_url` / `collected_at` | 수집 근거 |

### 금액

| 한국어 | 식별자 | 정의 |
|---|---|---|
| 기본료 | `basePrice` | 요금제 정가, VAT 포함 |
| 정가 | `listPrice` | 구독 티어 정가 |
| 실질 총비용 | `effectiveMonthlyCost` | 유일한 비교 기준 |
| 비교 기준선 | `baseline` | 할인 없이 정가 합 |
| 월 절감액 | `monthlySavings` | `baseline - effectiveMonthlyCost` |
| 선택약정할인 | `selectiveContractDiscount` | 월정액의 25% |
| 약정할인 | `planContractDiscount` | 요금제 고유 1년/2년 |
| 결합할인 | `bundleDiscount` | |
| 할인반환금 | `earlyTerminationFee` | `penalty` 금지 |
| 전환비용 | `switchingCost` | 위약금+잔여할부+재약정손실 |
| 회수기간 | `paybackMonths` | `breakEven` 금지 |

**`selectiveContractDiscount`와 `planContractDiscount`를 합치지 않는다.**
둘 다 한국어로 "약정할인"이지만 계산 시점과 근거가 다르다 (§4).

### 값 객체 · Enum

| 한국어 | 식별자 | 값 |
|---|---|---|
| 금액 | `Money` | `long` 원 단위 래핑 |
| 항목별 내역 | `CostBreakdown` / `CostLine` | |
| 금액 + 출처 쌍 | `ValuedAmount` | `CostLine`이 내부에 담는다 |
| 출처 | `Provenance` | `OFFICIAL` `DERIVED` `USER_PROVIDED` `ESTIMATED` |
| 혜택 유형 | `BenefitType` | `FREE` `FIXED_DISCOUNT` `RATE_DISCOUNT` `BUNDLE_INCLUDED` |
| 망 종류 | `NetworkType` | `FIVE_G` `LTE` `THREE_G` |
| 약정 유형 | `ContractType` | `NONE` `SELECTIVE_25` `DEVICE_SUBSIDY` |
| 정확도 | `Accuracy` | `FULL` `PARTIAL` |
| 탐지 규칙 | `DetectionRule` | `BENEFIT_OVERLAP` `TIER_DUPLICATE` `BUNDLE_OVERLAP` |

---

## 3. 혜택 유형

혜택 형태를 코드 분기가 아니라 **데이터로** 표현한다. 요금제가 추가돼도 코드를 안 고치기 위해서다.

| type | 의미 | `discount_value` | 계산 |
|---|---|---|---|
| `FREE` | 무료 제공 | — | 해당 티어 → 0원 |
| `FIXED_DISCOUNT` | 정액 할인 | 원 | `max(0, 정가 - value)` |
| `RATE_DISCOUNT` | 정률 할인 | 0.0~1.0 | `floor(정가 × (1 - value))` |
| `BUNDLE_INCLUDED` | 번들 포함 | — | 번들가를 구성 서비스에 분담 |

`is_exclusive = true` 혜택들은 **택1**이다 (예: "OTT 3사 중 하나"). 사용자가 원하는
서비스 집합 S와의 교집합 중 **절감액이 가장 큰 것 하나**를 고른다. 목록 순서로 고르면 오답이다.

---

## 4. 할인 적용 순서

정률과 정액의 순서에 따라 결과가 달라지므로 `DiscountRule.priority()`로 고정한다.

| priority | 규칙 | 대상 |
|---|---|---|
| 100 | 약정할인 (요금제 고유) | 기본료 |
| 200 | 선택약정 25% | 약정할인 적용 후 금액 |
| 300 | 결합할인 | 위 결과 |
| 400 | 프로모션 | 위 결과 |
| 500 | 구독 제휴 혜택 | 구독료 (통신비와 별개) |

---

## 5. Provenance

| 값 | 의미 | 예 |
|---|---|---|
| `OFFICIAL` | 공식 공시 | 요금제 월정액, OTT 정가 |
| `DERIVED` | 공식에서 계산 | 선택약정 25%, 약정 만료일 |
| `USER_PROVIDED` | 사용자 입력 | 데이터 사용량, 구독 목록 |
| `ESTIMATED` | 추정 | 위약금, OCR 추출값 |

`ESTIMATED`가 포함된 결과는 화면에 "추정치예요" 표기가 붙는다.

---

## 6. 조합 탐색

사용자가 원하는 서비스 집합 S를 **고정**하므로 조합 폭발이 없다.

```
후보 = 데이터 요구량을 만족하는 요금제 (수백 개)
각 후보마다 실질월비용 1회 계산 → O(N × |S|)
정렬 후 상위 3~5개
```

N≈300, |S|≈5면 밀리초다. **조기 최적화 금지.**

---

## 7. 중복 결제 탐지 (Phase 1)

| 규칙 | 케이스 | 근거 |
|---|---|---|
| `BENEFIT_OVERLAP` | 요금제 제휴로 무료인데 직접 결제 중 | `plan_benefit` ∩ `user_subscription` |
| `TIER_DUPLICATE` | 같은 서비스를 두 티어로 결제 | 동일 `service_id` 복수 활성 |
| `BUNDLE_OVERLAP` | 번들 포함인데 개별 결제 | 번들 구성표 대조 |

`BENEFIT_OVERLAP`이 임팩트가 가장 크다. 먼저 구현한다.

**미사용 판정**: 결제 중이나 "최근 30일 시청 안 함"으로 자기보고한 구독.
출처는 `ESTIMATED`, 해지를 강권하지 않고 근거만 제시한다.

---

## 8. 변경 시점 (Phase 2)

```
전환비용 = 할인반환금 + 잔여 할부금 + 재약정 손실
월절감액 = 현재 실질월비용 - 추천 조합 실질월비용
회수개월 = ceil(전환비용 / 월절감액)

월절감액 <= 0        → NO_BENEFIT
회수개월 < 약정잔여   → SWITCH_NOW
그 외                → WAIT_UNTIL_EXPIRY (만료일 명시)
```

이 계산 하나가 "변경 시점 추천"과 "프로모션 종료 알림"의 공통 엔진이다.

---

## 9. 사용자 입력 최소 집합

| 구분 | 필드 | 없으면 |
|---|---|---|
| **필수** | 월 데이터 사용량(GB) | 추천 불가 |
| **필수** | 원하는 서비스 목록 | 추천 불가 |
| 선택 | 현재 통신사 | 번호이동 판단 불가 |
| 선택 | 약정 유형·가입일 | 변경 시점 계산 불가 |
| 선택 | 가족 결합 여부 | 결합할인 미반영 |
| 선택 | 현재 구독 목록 | 중복 탐지 불가 |

선택 필드가 비면 응답의 `missingInputs`에 담아 **무엇을 더 주면 얼마나 정확해지는지**를
프론트가 그대로 렌더링하게 한다. 정책 "최소 입력 정보 표시"의 구현이다.
