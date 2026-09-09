# 아키텍처 · 규약

**§3 API 계약 표는 사람이 관리한다.** 에이전트는 임의 수정 없이 먼저 제안한다.
AI 서버 레포(`AI-/docs/contract.md`)에 사본이 있으므로 함께 맞춘다.

---

## 1. 패키지

```
com.palsaekjo.yogobi
├── pricing/          MVP  순수 도메인 — Spring 의존 금지
│   ├── domain/            Money, CostBreakdown, ValuedAmount, PricingContext
│   └── rule/              DiscountRule 구현체
├── catalog/          MVP  요금제·서비스·혜택 마스터 (읽기 전용)
├── recommend/        MVP  조합 탐색 + 추천 API
├── user/             P1   인증(JWT), 프로필
├── subscription/     P1   내 구독, 미사용 판정
│   └── port/              PaymentHistoryProvider
├── detection/        P1   중복 결제 탐지
├── chat/             P1   AI 서버 게이트웨이
├── alert/            P2   알림 스케줄러 (스텁까지만)
└── common/                예외, 응답 래퍼, 설정
```

의존 방향은 `AGENTS.md` 참조. 역방향이 생기면 설계가 틀린 것이다.

### 핵심 인터페이스

```java
public interface DiscountRule {
    boolean applies(PricingContext ctx);
    Money   apply(Money base, PricingContext ctx);
    int     priority();      // docs/domain.md §4
    String  label();         // CostBreakdown 항목명
}

public final class CostCalculator {
    public CostBreakdown calculate(MobilePlan plan, Set<ServiceTier> wanted, PricingContext ctx);
}

public interface PaymentHistoryProvider {   // subscription/port
    List<PaymentRecord> fetch(UserId userId);
}
// 구현: MockMydataProvider, EmlReceiptProvider, ManualEntryProvider
```

---

## 2. AI 서버 경계

| 책임 | BE_main | AI- |
|---|---|---|
| 금액 계산 | ✅ 단독 | ❌ 금지 |
| 조합 탐색 | ✅ | ❌ |
| 자연어 → 파라미터 | ❌ | ✅ |
| 결과 → 자연어 | ❌ | ✅ |
| 세션·이력 저장 | ✅ | ❌ 무상태 |

```
POST /api/v1/chat/messages  (BE_main)
  → AI: POST /parse    → { required, optional, confidence }
  → 내부 recommend 호출 (필터 경로와 동일 로직)
  → AI: POST /narrate  → { message }
```

**BE_main이 AI-를 호출한다. 반대 방향은 없다.** `confidence < 0.7`이면 되묻는다.
AI 서버가 죽어도 필터 경로는 정상 동작해야 한다.

### 왜 나눴나 — 핵심 근거

**금액의 신뢰 경계(trust boundary)가 유일한 핵심 이유다.** 요고비의 존재 이유는
"미사용 혜택 0원 + 실제 지불 총액 정확성"이다. LLM은 비결정적이라 같은 조건에 다른 숫자를
내놓을 수 있다(`docs/testing.md`: *"컴파일되지만 금액이 틀린 답을 잘 만든다"*). 그래서
**숫자를 만드는 주체(BE `pricing`)와 말을 만드는 주체(AI)를 물리적으로 분리**한다.

- 금액·조합은 `pricing`(순수 Java, 골든케이스·분기 100%)이 독점한다. **AI는 숫자를 만들지 않는다**
  (절대 원칙 2, D-03). AI가 하는 건 둘뿐: `/parse`(자연어→파라미터), `/narrate`(BE가 계산한 숫자를 문장으로 포장).
- 경계는 코드로 강제된다: `/narrate`는 LLM도 안 쓰는 **결정론적 템플릿**, BE `AiGateway`가 AI 응답
  (confidence·정수 GB·서비스 ID·enum)을 **전부 재검증**해 어긋나면 폐기, 필터·챗봇이 **같은 recommend 엔진**을 탄다(원칙 3).

**부수 근거(핵심은 아니지만 정당화):**
- **기술 적합성** — 결정론적 계산은 Java(BigDecimal·타입), 자연어 파싱/생성은 Python/LLM 생태계.
- **Fail-soft** — AI(외부 LLM 의존)가 죽어도 필터 추천은 정상, 챗봇만 `FILTER_FALLBACK`으로 우아하게 저하.
- **상태 경계** — BE가 세션·이력·DB를 소유, AI는 무상태. 스케일·배포 프로파일이 다르다.
- **독립 반복** — 프롬프트·모델 교체가 계산 엔진을 안 건드린다. 계약(§3)이 유일한 이음새(D-06).

**오해 방지:** 확장성을 위한 마이크로서비스 분해가 아니다(그건 스코프 아웃 — `AGENTS.md`).
"돈 vs 말" 사이에 넘으면 안 되는 정확성 경계 때문에 나눈 것이다. 스케일링은 부산물이지 목적이 아니다.

---

## 3. API 계약

### MVP
| Method | Path | 설명 |
|---|---|---|
| POST | `/api/v1/recommendations` | 무상태 추천 — 필터·챗봇 공용 |
| POST | `/api/v1/calculator` | 특정 조합 총비용 |
| GET | `/api/v1/catalog/plans` | 요금제 카탈로그 |
| GET | `/api/v1/catalog/services` | 구독 서비스·티어 |
| GET | `/api/v1/catalog/plans/{id}/benefits` | 요금제별 혜택 |

### Phase 1
| Method | Path | 설명 |
|---|---|---|
| POST | `/api/v1/auth/signup` `/login` `/logout` | 인증 |
| GET/POST/DELETE | `/api/v1/me/subscriptions` | 내 구독 |
| POST | `/api/v1/me/payments/import` | 결제내역 업로드 |
| GET | `/api/v1/me/detections` | 탐지 결과 |
| POST | `/api/v1/chat/messages` | 챗봇 |

### Phase 2
| Method | Path | 설명 |
|---|---|---|
| GET | `/api/v1/me/switch-timing` | 변경 시점 (회수기간) |
| GET | `/api/v1/me/alerts` | 종료 예정 목록 |

### 요청 / 응답

```jsonc
// POST /api/v1/recommendations
{
  "required": { "monthlyDataGb": 20, "wantedServiceIds": [1, 5] },
  "optional": { "currentCarrier": "SKT", "networkType": "5G",
                "contractType": "SELECTIVE_25", "hasFamilyBundle": true }
}
```

```jsonc
// 200
{
  "accuracy": "PARTIAL",
  "missingInputs": [
    { "field": "hasFamilyBundle",
      "impact": "가족 결합 시 최대 11,000원 추가 절감 가능",
      "howToFind": "통신사 마이페이지 > 결합 상품" }
  ],
  "results": [{
    "planId": 42, "planName": "5G 슬림+", "carrier": "SKT",
    "monthlyTotal": 71300, "baseline": 89000,
    "monthlySavings": 17700, "annualSavings": 212400,
    "breakdown": [
      { "label": "5G 슬림+ 기본료", "amount": 55000, "provenance": "OFFICIAL" },
      { "label": "선택약정 25% 할인", "amount": -13750, "provenance": "DERIVED" },
      { "label": "넷플릭스 스탠다드", "amount": 13500, "provenance": "OFFICIAL",
        "note": "제휴 혜택으로 4,000원 할인 적용" }
    ]
  }]
}
```

`baseline`은 아무 할인 없이 정가로만 냈을 때다. 절감액 표시의 기준선.

---

## 4. DB 스키마

### MVP
| 테이블 | 주요 컬럼 |
|---|---|
| `carrier` | id, name, carrier_type |
| `mobile_plan` | id, carrier_id, name, network_type, base_price, data_mb, voice_min, sms_cnt, contract_discount_12m, contract_discount_24m, source_url |
| `subscription_service` | id, name, category, official_url |
| `subscription_tier` | id, service_id, name, price, concurrent_streams, quality |
| `plan_benefit` | ↓ |
| `bundle_product` / `bundle_item` | id, name, price / bundle_id, tier_id |
| `merchant_alias` | id, service_id, pattern, match_type |

```sql
CREATE TABLE plan_benefit (
    id              BIGSERIAL PRIMARY KEY,
    mobile_plan_id  BIGINT NOT NULL REFERENCES mobile_plan(id),
    service_id      BIGINT NOT NULL REFERENCES subscription_service(id),
    tier_id         BIGINT REFERENCES subscription_tier(id),
    benefit_type    VARCHAR(20) NOT NULL,   -- FREE|FIXED_DISCOUNT|RATE_DISCOUNT|BUNDLE_INCLUDED
    discount_value  NUMERIC(10,2),
    is_exclusive    BOOLEAN NOT NULL DEFAULT FALSE,
    exclusive_group VARCHAR(50),
    valid_from      DATE,
    valid_to        DATE,
    source_url      TEXT
);
CREATE INDEX idx_plan_benefit_plan ON plan_benefit(mobile_plan_id);
```

### Phase 1 (V2 마이그레이션)
`app_user`(id, email, password_hash, **current_plan_id** → mobile_plan, created_at)
— `user` 는 PostgreSQL 예약어라 `app_user`. `current_plan_id` 는 BENEFIT_OVERLAP 탐지에 쓰는 현재 요금제.
· `user_subscription`(user_id, tier_id, started_at, ended_at, monthly_price, last_used_at)
· `payment_record`(user_id, merchant_raw, service_id nullable, amount, paid_at, source)
· `detection_result`(user_id, rule_code, target_ref, wasted_amount, detected_at)

### Phase 2 (스키마만 선반영)
`contract` · `promotion` · `alert_schedule`

### 초기 구현 범위 (D-07)
V1은 위 MVP 테이블 8개를 생성한다. P1/P2 테이블은 해당 단계에서 새 마이그레이션으로 추가한다.
시드 원문 보존을 위해 `subscription_tier.note`, `bundle_product.provider`를 저장하고,
`mobile_plan`에 `age_limit`·`collected_at`, `plan_benefit`에 `collected_at`을 둔다.
시드 3종의 적재 방식은 `db/seed/README.md`를 따른다.

---

## 5. 개발 규약

### 커밋
```
feat(pricing): 선택약정 25% 할인 규칙 추가
fix(detection): 번들 중복 판정에서 비활성 구독 제외
```
타입 `feat|fix|refactor|test|docs|chore`, 스코프는 패키지명. 본문에 **왜**를 쓴다.
커밋 author·메시지에 AI 귀속 흔적(에이전트 태그·`Co-Authored-By`)을 남기지 않는다. author 는 `winwinhun`.

### 브랜치
`main`(항상 동작) + `feat/<주제>`(하루 안에 머지). GitFlow는 과하다.

### 마이그레이션
Flyway `db/migration/V{n}__{설명}.sql`.
**적용된 파일을 수정하지 않는다.** 새 파일을 추가한다.
시드는 마이그레이션이 아니라 `db/seed/*.csv` + 로더로 넣는다.

### 에러 코드
| 코드 | 의미 | HTTP |
|---|---|---|
| `YGB-REQ-001` | 필수 입력 누락 | 400 |
| `YGB-CAT-001` | 요금제 없음 | 404 |
| `YGB-CAL-001` | 계산 가능한 조합 없음 | 422 |
| `YGB-IMP-001` | 결제내역 파일 형식 오류 | 400 |
| `YGB-IMP-002` | 인식 불가 가맹점 포함 | 200 + 경고 |
| `YGB-EXT-001` | 외부 API 실패 | 200 + 경고, **추천은 정상 반환** |

```json
{ "data": {...}, "warnings": [{ "code": "...", "message": "..." }] }
{ "error": { "code": "...", "message": "...", "field": "..." } }
```

### 환경 변수
`.env.example`을 커밋하고 `.env`는 gitignore. **API 키를 코드·문서에 적지 않는다.**

### Definition of Done
1. 골든 케이스 또는 단위 테스트 green
2. API를 추가했으면 §3 표 갱신 (+ AI- 레포 사본)
3. **API 요청/응답이 바뀌면 `docs/BE_API.md`(프론트 참고본) 갱신**
4. 새 개념을 만들었으면 `docs/domain.md` §2에 등록
5. `docs/state.md` 갱신
6. `docker compose down -v && up`으로 처음부터 재현

6번이 자주 빠진다. 내 로컬 DB에만 있는 데이터로 동작하면 완료가 아니다.
