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
  → AI: POST /narrate  → { message, reasons }

POST /api/v1/admin/catalog/{dataset}  (운영자 변경 제안, D-29)
  → 스마트초이스 Open API  → 공식 시세 대조 (1차)
  → AI: POST /catalog/candidates → { status, candidate, confidence, sources } (2차 더블체크)
  → 판정은 BE 가 한다: VERIFIED / MISMATCH(승인 차단) / UNVERIFIED(통과 후 사용자 제보로 보완)
```

**BE_main이 AI-를 호출한다. 반대 방향은 없다.** `confidence < 0.7`이면 되묻는다.
AI 서버가 죽어도 필터 경로는 정상 동작해야 한다.

프론트는 BE_main API만 호출하며 AI 서버와 직접 통신하지 않는다.
프론트 연동 명세는 `docs/BE_API.md`, AI 서버 간 계약은 `AI-/docs/contract.md` 사본과 AI README에서 확인한다.
BE와 AI에 같은 비밀 환경 변수 `AI_INTERNAL_TOKEN`을 설정하고,
BE가 `Authorization: Bearer <AI_INTERNAL_TOKEN>`으로 호출한다. 사용자 인증 헤더는 AI에 전달하지 않는다.
AI의 `/parse`·`/narrate`·`/ocr`는 토큰 누락·불일치 시 401, 서버 토큰 미설정 시 503 (`AI-AUTH-001`)로 차단한다.
헬스체크는 토큰 없이 사용한다. 배포 시 AI 접근은 사설망 또는 BE만 허용한 네트워크로 제한한다.
이는 사용자 JWT 인증과 별도이며 AI 서버에 사용자 DB·세션을 추가하지 않는다.

### 왜 나눴나 — 핵심 근거

**금액의 신뢰 경계(trust boundary)가 유일한 핵심 이유다.** 요고비의 존재 이유는
"미사용 혜택 0원 + 실제 지불 총액 정확성"이다. LLM은 비결정적이라 같은 조건에 다른 숫자를
내놓을 수 있다(`docs/testing.md`: *"컴파일되지만 금액이 틀린 답을 잘 만든다"*). 그래서
**숫자를 만드는 주체(BE `pricing`)와 말을 만드는 주체(AI)를 물리적으로 분리**한다.

- 금액·조합은 `pricing`(순수 Java, 골든케이스·분기 100%)이 독점한다. **AI는 숫자를 만들지 않는다**
  (절대 원칙 2, D-03). AI가 하는 건 둘뿐: `/parse`(자연어→파라미터), `/narrate`(BE가 계산한 숫자를 문장으로 포장).
- 경계는 코드로 강제된다: `/narrate`의 **금액 문장(`message`)은 LLM을 쓰지 않는 결정론적 템플릿**이고,
  **추천 사유(`reasons`)만 LLM이 만들되 BE가 보낸 금액 외의 금액이 섞이면 그 줄을 버린다**(D-26).
  BE `AiGateway`가 AI 응답(confidence·정수 GB·서비스 ID·enum)을 **전부 재검증**해 어긋나면 폐기,
  필터·챗봇이 **같은 recommend 엔진**을 탄다(원칙 3).

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
| GET | `/api/v1/catalog/services` | 구독 서비스·티어. 등급에 `currency`(KRW\|USD)·`taxIncluded`·`krwEstimate`·`krwRateDate` — 해외 결제는 환산 **표시만** (아래) |
| GET | `/api/v1/catalog/plans/{id}/benefits` | 요금제별 혜택 |
| POST | `/api/v1/catalog/reports` | 정보 오류 제보(비회원 허용·CSRF 필수), 접수만 수행 — D-18 사용자 요청 |
| GET | `/api/v1/admin/catalog` | 카탈로그 원본(합본 CSV) 데이터셋 목록·행 수 — **운영자 전용**, D-24 |
| GET | `/api/v1/admin/catalog/{dataset}` | 데이터셋 전체 행 |
| POST | `/api/v1/admin/catalog/{dataset}` | 행 추가 **제안** — 202, 승인 전까지 반영 없음 (D-28) |
| PATCH | `/api/v1/admin/catalog/{dataset}/{key}` | 행 부분 수정 **제안** — 202 |
| DELETE | `/api/v1/admin/catalog/{dataset}/{key}` | 행 삭제 **제안** — 202 |
| GET | `/api/v1/admin/catalog/requests` | 제안 목록. `?status=PENDING\|APPROVED\|REJECTED\|FAILED` |
| POST | `/api/v1/admin/catalog/requests/{id}/approve` | 승인 — **이때 파일·DB에 반영**. 재승인·검토 불일치는 409 (D-29) |
| POST | `/api/v1/admin/catalog/requests/{id}/reject` | 거절 — `{reason}`, 영영 반영하지 않음 |
| GET | `/api/v1/admin/catalog/audit` | 원본 변경 이력(최신순, `?limit=1~500`) — 행위자·시각·전/후 행·결과, D-27 |
| POST | `/api/v1/admin/login` | 백오피스 운영자 로그인 `{id,password}` — 공개, 실패는 401 하나 (D-32) |
| GET | `/api/v1/admin/session` | 관리자 여부 확인 |
| GET | `/api/v1/admin/dashboard` | 사용 지표(회원·카탈로그·검수·제보·엔드포인트) |
| POST | `/api/v1/admin/harvest/run` | 일일 수집 즉시 실행(정기: 매일 09:00 KST) |

### Phase 1

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/v1/auth/logout` `/logout-all` | 로그아웃 · 이 회원의 모든 로그인 무효화 |
| GET | `/api/v1/auth/csrf` | 회원 변경·비회원 제보 요청용 CSRF 토큰 |
| GET | `/oauth2/authorization/google` → `/login/oauth2/code/google` | **유일한 가입·로그인 경로**(D-34). 콜백 후 `AUTH_RETURN_URL#auth=...` |
| POST | `/api/v1/me/nickname` | 닉네임 변경 — `{nickname}` (D-22) |
| GET | `/api/v1/me` | 인증된 현재 회원 조회 |
| GET/DELETE | `/api/v1/me/sessions` `/{id}` | 로그인 세션 목록 · 개별 세션 폐기 |
| GET/POST/DELETE | `/api/v1/me/subscriptions` `/{id}` | 내 구독 (구현) |
| POST | `/api/v1/me/current-plan` | 현재 요금제 설정 (구현) |
| POST | `/api/v1/me/payments/import` | 결제내역 업로드 |
| GET | `/api/v1/me/detections` | 탐지 결과 (구현 — 요청 시 재탐지) |
| POST | `/api/v1/chat/messages` | 챗봇 |

### Phase 2
| Method | Path | 설명 |
|---|---|---|
| GET | `/api/v1/me/switch-timing` | 변경 시점 (회수기간) — 구현. `?targetPlanId&switchingCost&remainingContractMonths`, 현재 요금제(저장)+구독 대비 회수개월·SWITCH_NOW/WAIT/NO_BENEFIT |
| GET | `/api/v1/me/alerts` | 종료 예정 목록 |

### 개인정보 (V5 — 구현)
| Method | Path | 설명 |
|---|---|---|
| GET | `/api/v1/privacy-policy` | 처리방침·처리 인벤토리(공개) — 항목·목적·보유기간·정보주체 권리 |
| DELETE | `/api/v1/me` | 회원 탈퇴 — 일반 이용 데이터 파기·세션 무효화·쿠키 삭제. 법정 보존 사본은 별도 확정 기한 적용 |
| GET | `/api/v1/me/consent` | 내 수집·이용 동의 조회 |
| POST | `/api/v1/me/consent/marketing` | 선택(마케팅) 동의/철회 `{agree}` |
| POST | `/api/v1/me/consent/acknowledge` | 바뀐 처리방침 확인 기록 — **필수 항목만** 현재 버전으로. 마케팅 동의는 승계하지 않는다 (2026-09-17) |

처리방침·인벤토리·보유기간은 `docs/privacy.md`. 보유기간·동의 항목은 정책값이라 확정 시 함께 갱신한다.

### 요청 / 응답

회원 인증 계약(2026-09-10 사용자 승인 1~4): `docs/auth.md`.
추천·계산기·카탈로그·단일 발화 챗봇 **엔드포인트는 비회원에게 공개한다.** 개인 데이터 저장·관리는 회원 전용이다.
단 **결과 리포트 화면은 로그인 후에만 그린다**(D-36) — 화면 게이트이며 API 권한이 아니다. API 를 직접 부르면 우회되고, 그것을 알고 둔 선택이다.
자체·Google 로그인 모두 동일한 내부 `userId`와 15분 JWT를 사용한다(refresh 없음, 만료 후 재로그인). JWT는 HttpOnly 쿠키로만 전달하며
별도 브라우저 확인 쿠키와 DB 발급 지문을 함께 검사한다. 회원 요청은 `credentials: include`, 변경 요청은 CSRF 헤더가 필요하다.
자체 가입은 `{name,email,password,nickname?}` 한 번으로 끝난다(D-21). **메일은 쓰지 않는다** — 본인 확인 메일·메일 토큰 가입·메일 재설정
엔드포인트와 `AuthEmail`·`spring-boot-starter-mail`을 2026-09-16 에 제거했다. 이메일 소유는 확인하지 않으며 `email_verified`는 자체 가입에서 FALSE 로 남는다.
**우리는 회원 비밀번호를 보관하지 않는다**(D-34). 그래서 재설정도 복구 코드도 없다 — 계정 복구는 Google 이 한다.
이메일만 같다고 계정을 합치지 않는다. 자체 계정에서 비밀번호 재확인 후 같은 이메일의 Google 계정을 명시적으로 연결한다.
Google 전용 계정은 동일 Google `sub` 재인증으로 자체 비밀번호를 추가한다. 연결 완료 시 기존 세션을 모두 무효화한다.

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
    ],
    "priceCrossCheck": {                                // 스마트초이스 공식 시세 대조(D-20 1차 교차검증)
      "status": "MATCH",                                // MATCH | MISMATCH | UNVERIFIED | NOT_APPLICABLE
      "officialPrice": 55000,                           // 확인 못 했으면 null (0원으로 적지 않는다)
      "source": "스마트초이스(KTOA)", "sourceUrl": "https://www.smartchoice.or.kr/",
      "checkedAt": "2026-09-16T03:40:00Z"               // 스냅샷을 모은 시각
    }
  }],
  "reasons": [                                            // 1순위 조합에 대한 사유, 0~3개
    "따로 내시던 넷플릭스 스탠다드 13,500원이 요금제에 포함돼 있어요.",
    "선택약정 25% 할인으로 월 13,750원이 빠져요."
  ]
}
```

`baseline`은 아무 할인 없이 정가로만 냈을 때다. 절감액 표시의 기준선.
D-18에서 우체국·스마트초이스 연동과 `priceCrossCheck` 응답 필드를 제거했다.
**2026-09-16 사용자 승인으로 `priceCrossCheck`를 복구했다**(D-12 마지막 줄이 남겨 둔 §3 계약 결정).
대조 대상은 요금제 **기본료**이며 `baseline`(구독 포함 정가 합계)이 아니다.
`status`는 네 값이다. `UNVERIFIED`는 **"틀렸다"가 아니라 "확인 못 했다"**이고,
`NOT_APPLICABLE`은 **스마트초이스가 그 통신사를 아예 주지 않아 대조 대상이 아니다**라는 뜻이다.
실측 응답의 통신사는 `SKT·KT·LGU+` 뿐이라 알뜰폰 요금제(카탈로그 1,711건 중 1,451건)가 여기 해당한다
(evidence/smartchoice-operation-2026-09-16.json). 둘을 합치면 화면이 알뜰폰 사용자에게 영영 오지 않을
"확인"을 기다리게 만든다.
통신사 표기는 소문자·공백 제거 후 비교한다 — 카탈로그 `LG U+` 와 응답 `LGU+` 가 달라 88건이 전부 실패했다.
값은 **표시·신뢰용이며 금액에도 추천 순위에도 넣지 않는다**(D-03). 서비스는 정렬이 끝난 뒤에 붙인다.
채우는 값의 출처는 배치가 모은 `smartchoice_plan_snapshot` 뿐이다 — 요청 경로는 외부를 호출하지 않는다(D-05 유지).
`SMARTCHOICE_API_KEY`가 없으면 스윕이 돌지 않아 전 결과가 `UNVERIFIED`다(fail-soft).
D-26에서 AI `/narrate` **응답**에 `reasons`를 더했다. 요청 필드는 그대로다.
D-26 후속(2026-09-16): BE가 `/narrate`를 호출해 `reasons`를 `/recommendations` 응답 최상위에 싣는다.
필터·챗봇 두 경로 모두 1순위 결과(`results[0]`)에 대한 사유를 담으며, AI 장애 시에도 `results`는 정상이다.
narrate 오케스트레이션은 컨트롤러가 한다(`RecommendationController`·`ChatController`) — `RecommendationService`는 AI를 모른다.
`recommend`가 `chat`의 `AiGateway`에 직접 의존하면 순환이 되므로 포트 `recommend.Narrator`(구현: `AiGateway`)로 역전한다.

**`/narrate` 요청에 싣는 필드는 아래 9개뿐이다**(`AiGateway.NARRATE_FIELDS`):
`planId`·`planName`·`carrier`·`monthlyTotal`·`baseline`·`monthlySavings`·`annualSavings`·`breakdown`·`missingInputs`.
`CostResult`를 통째로 직렬화하면 AI가 쓰지 않는 필드까지 나간다 — 복구된 `priceCrossCheck`가 실제로 그랬다.
AI는 계약 밖 필드를 **422로 거부**하고 BE는 그것을 장애로 삼키므로, 사유가 화면에서 조용히 사라진다.
레코드에 필드를 더하면 이 목록에 적을지 먼저 정한다. 적지 않으면 AI로 가지 않는다.

```jsonc
// AI: POST /narrate 200
{
  "message": "“SKT 5G 슬림+”의 실제 내시는 금액은 월 71,300원이에요. ...",  // 고정 템플릿
  "reasons": [                                                            // 0~3개. 모델 또는 규칙
    "따로 내시던 넷플릭스 스탠다드 13,500원이 요금제에 포함돼 있어요.",
    "선택약정 25% 할인으로 월 13,750원이 빠져요."
  ]
}
```

`reasons`는 화면의 "왜 나에게 이 상품이 추천됐나요?" 목록을 채운다. 보조 정보이므로 **비어 있을 수 있다.**
**모델 장애 시에는 AI 서버가 규칙으로 만든 사유가 내려간다**(D-38, 사용자 승인 2026-09-17).
요청의 `breakdown`만 읽어 만들며 모델 문장과 **같은 금액 가드**를 통과하므로 나가는 규칙은 하나다.
근거가 없으면 빈 배열도 여전히 가능하다. `message`와 추천 결과는 그 경우에도 정상이다.
AI는 요청의 `breakdown`·`missingInputs`에 **실제로 있는 금액만** 인용하며, 그 밖의 금액이 섞인 줄은 AI 서버가 폐기한다.
`breakdown`에 없는 항목은 근거로 쓰지 않으므로 **미사용 혜택은 사유 문장에도 등장하지 않는다**(절대 원칙 1).
카탈로그 원본은 검수·승인된 CSV이며 PostgreSQL에 반영된 값으로 계산한다. 발행·복구 절차는 `docs/catalog-data.md`,
제보 요청·응답 상세는 `docs/BE_API.md`를 따른다. 제보는 카탈로그를 직접 수정하지 않는다.

```jsonc
// GET /api/v1/catalog/services 200 — 등급(tier) 스키마 (사용자 결정 2026-09-16)
{ "id": 2,  "name": "스탠다드", "price": 13500, "currency": "KRW", "taxIncluded": true,
  "krwEstimate": null,  "krwRateDate": null },
{ "id": 94, "name": "Pro",      "price": 20,    "currency": "USD", "taxIncluded": false,
  "krwEstimate": 29901, "krwRateDate": "2026-09-15" }   // 환율 환산 + 부가세 10% — ESTIMATED
```

`price`는 **`currency` 단위의 공식 표기 금액**이다. `taxIncluded=false` 면 표기가가 세금 별도라는 뜻이고
(해외 사업자 관행 — 한국 이용자는 결제 시 부가세 10%가 더 붙는다) `krwEstimate` 에 그 10%가 반영돼 있다.
**표기가에 세금을 섞어 저장하지 않는다** — 공식 표기가가 출처다. 해외 결제 등급은 원화 확정 금액이 없으므로
`krwEstimate`(환산)와 기준일 `krwRateDate`를 함께 주고, 원화 등급은 두 필드가 `null`이다.
환율은 **하루 1회 배치**로만 갱신하며(요청 경로에서 외부 호출 없음 — D-05) 값이 없으면 환산도 `null`이다. 0원으로 적지 않는다.
**환산값은 표시 전용이다**(D-17): `/recommendations`·`/calculator`는 원화 확정 등급만 계산에 넣고,
빠진 서비스·등급은 400이 아니라 `missingInputs`로 알린다. 계산에 들어가는 유일한 원화 금액은
사용자가 확인해 넣은 `user_subscription.monthly_price`(`USER_PROVIDED`)다. 골든 케이스는 `docs/testing.md` G-17.

---

## 4. DB 스키마

### MVP
| 테이블 | 주요 컬럼 |
|---|---|
| `carrier` | id, name, carrier_type |
| `mobile_plan` | id, carrier_id, name, network_type, base_price, data_mb, voice_min*, sms_cnt*, contract_discount_12m, contract_discount_24m, source_url (*는 미확인 시 NULL, V11) |
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

### 회원 인증 (V3·V4 마이그레이션)
`app_user.password_hash`는 Google 전용 회원에서 null 가능, `google_sub`는 UNIQUE, 정규화 이메일 UNIQUE, 로그인 수단 최소 1개.
V4에서 `email_verified`(자체 가입은 검증 토큰 소비 시 TRUE), `credential_version`(비밀번호·연결 변경 시 증가 → 이전 발급 토큰 무효화 기준) 추가.
`auth_session`(token_hash PK, user_id FK, binding_hash, expires_at + V4: id UUID, created_at, last_seen_at, user_agent):
원문 JWT·브라우저 확인값은 저장하지 않고 SHA-256 지문만. 15분 만료·5분 유휴로 정리하며 `/me/sessions`로 조회·폐기한다.
`auth_email_token`(token_hash PK, purpose SIGNUP|RESET, email, user_id, credential_version, expires_at): 10분·단일 사용 본인 확인 토큰.
이메일별 트랜잭션 잠금 후 소비해 동시 링크 정리의 교착을 방지한다.
자격 증명 변경과 세션 발급은 회원 행/버전을 검사하며 재설정 전 로그인 결과의 뒤늦은 발급을 차단한다.
`auth_rate_limit`(bucket PK, expires_at, attempts): IP 40·이메일 로그인 10·재인증 10·메일 3, 15분 창. 만료 행은 요청 시 정리.

### 개인정보 (V5 마이그레이션)
삭제권(파기): V2 개인 테이블(`user_subscription`·`payment_record`·`detection_result`) FK에 `ON DELETE CASCADE` 부여.
`DELETE app_user`로 일반 이용 데이터가 파기된다(auth_session·auth_email_token은 V3/V4에서 이미 cascade). V6의 별도 법정 보존 사본은 이 FK 경로에 연결하지 않는다.
`user_consent`(id, user_id FK cascade, item `ESSENTIAL|MARKETING`, policy_version, agreed_at, withdrawn_at, UNIQUE(user_id,item)):
필수는 가입 시 자동 기록(계약 이행), 선택은 `/me/consent/marketing`로 동의/철회. 보유기간 초과분은 `RetentionService`가 파기.

### 법정 보존 예외 (V6, 2026-09-12 팀원 리뷰 반영)
`payment_record`는 사용자 반입 외부 구독 분석 내역이며 CASCADE·12개월 보유 정책을 유지한다.
법정 의무가 확인된 예외 사본만 `retained_payment_record`에 분리 저장한다. 원본 거래 ID·가맹점·서비스·금액·결제일·출처,
`legal_basis`·`retention_start`·`retain_until`·저장 시각을 보관하며 회원 ID·이메일·인증 정보·회원/원본 FK는 없다.
`PaymentRetentionService.preserve`는 원본 생성/분류 트랜잭션에서 명시적으로 호출하고, 일반 반입/탈퇴가 자동으로 사본을 생성하지 않는다.
`RetentionService`는 사본의 확정 기한에만 파기한다. 회원 API·추천·AI에 사본 접근 경로 없음. 익명화를 보장하지 않으며 상세 조건은 `docs/privacy.md`.

### CSV 운영·정보 오류 제보 (V9, D-18)
V7의 `smartchoice_plan_snapshot`은 V9에서 제거한다. 기존 Flyway V7 파일은 적용 이력 때문에 유지한다.
`mobile_plan`·`subscription_service`·`subscription_tier`·`bundle_product`에 `active`를 추가한다.
CSV에 없는 상품은 비활성화하며 기존 회원 FK를 보존한다. 신규 카탈로그 조회·추천·선택은 활성 상품만 허용한다.
`CatalogCsvSync`는 승인 해시를 확인한 5종 전체 CSV를 단일 DB 트랜잭션으로 반영한다. 실패하면 이전 DB를 유지한다.
`catalog_report`는 UUID, 대상 종류/ID, 오류 항목, 설명, 선택 출처 URL, 상태, 생성 시각을 저장한다.
회원 ID·이메일·원문 IP를 수집하지 않으며 제보 본문은 90일 경과 후 정기 파기한다.

### 카탈로그 결손 기록 (V10)
`catalog_candidate`는 추천에서 찾지 못한 서비스 ID·요금제 조건의 요청 횟수를 기록한다. 금액 계산에 쓰지 않는다.
기존 정보 정정용 `catalog_report`와 별도이며, 실제 AI 수집·검증 작업의 실행기는 아직 연결하지 않았다.

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
`funnel_daily(day, kind, count)` — D-36 로그인 게이트 퍼널 일별 집계(V20). 개인 식별값이 없어 보유기간 판단 대상이 아니다. micrometer 는 인메모리라 Fly auto_stop 에 리셋돼 쓸 수 없다.
**적용된 파일을 수정하지 않는다.** 새 파일을 추가한다.
시드는 마이그레이션이 아니라 `db/seed/*.csv` + 로더로 넣는다.

### 에러 코드
| 코드 | 의미 | HTTP |
|---|---|---|
| `YGB-REQ-001` | 필수 입력 누락 | 400 |
| `YGB-CAT-001` | 요금제 없음 · 카탈로그 원본의 행/데이터셋 없음(D-24) | 404 |
| `YGB-CAT-002` | 카탈로그 원본 행 충돌 — 키 중복·키 필드 변경 시도(D-24) | 409 |
| `YGB-CAT-503` | 카탈로그 원본 파일 경로 미설정 — **편집만** 불가(조회는 됨). `yogobi.catalog.combined-csv` 가 비면 발생 (2026-09-17) | 503 |
| `YGB-CAL-001` | 계산 가능한 조합 없음 (D-17 이후 미사용 — 추천의 후보 0건은 200+`missingInputs`) | 422 |
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
