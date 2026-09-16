# 데이터

**CSV 원본은 D-18 및 [CSV 카탈로그 운영](catalog-data.md)을 따른다. 스마트초이스는 D-23(검증 오버레이 복구)으로 일부 대체됐다.**

## 1. 데이터 원본과 조회

카탈로그는 팀의 검수 CSV가 원본이며 PostgreSQL에 반영해 조회한다.
AI 모델의 출력은 이용 조건을 확인한 자료에서 추출한 후보로 받아 검수 후 CSV에 반영한다.
우체국 연동은 제거했다. 스마트초이스 클라이언트는 검증용으로 복구됐지만 배치 수집·시세 교차검증 호출은 미연결이다.
과거 출처 표기는 실제 자료의 출처로 보존한다.

| 데이터 | 관리 파일 | 현재 확보 |
|---|---|---|
| 구독 서비스 | subscription_service.csv | 6건 |
| 구독 티어 | subscription_tier.csv | 17건 |
| 번들 | bundle_product.csv | 7건 |
| 통신 요금제 | mobile_plan.csv | **1,706건** (2026-09-16 팀 수집 매트릭스 1,853행 → `catalog_matrix_to_csv.py` 변환·검수 대기) |
| 제휴 혜택 | plan_benefit.csv | **46건** (FREE 7 · FIXED_DISCOUNT 7 · BUNDLE_INCLUDED 32 — 아래 §1-C) |

### 원천 데이터셋 = 합본 CSV (D-24, 2026-09-16)

위 5개는 **`db/seed/catalog_combined.csv` 한 파일로 합쳐 원천 데이터셋**이 됐다. 개별 파일은 변환 이력으로 남아 있다.

```
#@ mobile_plan            ← 통신요금 1,706행
#@ subscription_service   ← 구독서비스 6행
#@ subscription_tier      ← 구독티어 17행
#@ plan_benefit           ← 제휴혜택 46행
#@ bundle_product         ← 번들 7행
```

- `#@ <dataset>` 줄이 섹션 경계, 각 섹션 첫 비주석 줄이 CSV 헤더다. 그 밖의 `#` 줄은 주석. 줄바꿈은 **LF**.
- **원본은 이 파일, DB는 투영이다.** 쓰기는 파일(원자적 교체) → DB 전체 재적재 순서이며 DB 실패 시 파일을 되돌린다.
- 활성화: `yogobi.catalog.combined-csv=<경로>`. 설정되면 시작 시 이 파일이 적재되고 개별 시드 적재는 건너뛴다.
  `CATALOG_CSV_DIR`(승인·해시 잠금 모드)가 켜져 있으면 그쪽이 우선이며 합본은 관여하지 않는다.
- CRUD: `GET/POST/PATCH/DELETE /api/v1/admin/catalog/...` — **운영자 전용**. `CATALOG_ADMIN_USER_IDS`에 지정된 회원만
  `ROLE_ADMIN`을 받고, 비어 있으면 아무도 쓸 수 없다(기본 잠금). 일반 회원은 403. 키는 데이터셋별 자연키를 `|`로 이은 값
  (요금제 `SKT|베스트 Max(T 우주)`, 서비스·티어·번들은 `id`, 혜택은 `통신사|요금제명|service_id|tier_id`).
- 삭제는 파일에서 행을 지우고 DB에서는 `active=false`로 내린다(회원 참조 보존).
- **쓰기는 제안·승인 2단계다**(D-28): `POST/PATCH/DELETE`는 `catalog_change_request`(V15)에 PENDING 제안을 만들고 202를 준다.
  `POST /api/v1/admin/catalog/requests/{id}/approve` 로 승인해야 파일·DB에 반영된다. 자기 승인은 허용하되 제안자·승인자를 따로 남긴다.
- **제안 내용은 자동 검토된다**(D-29): ① 스마트초이스 시세 ② AI `POST /catalog/candidates` 두 소스와 금액을 대조한다(±100원).
  한 곳이라도 **다른 금액**이면 `MISMATCH` — 승인이 막힌다. 둘 다 확인 못 하면 `UNVERIFIED` 로 **통과시키고 사용자 제보로 보완**한다(D-18).
  판정은 BE 가 한다 — AI 는 출처를 찾아 보고할 뿐 숫자를 확정하지 않는다(절대 원칙 2).
- **모든 변경은 `catalog_audit`(V14)에 남는다**(D-27): 행위자·시각·데이터셋·행 키·변경 전/후 행·결과(APPLIED/FAILED).
  원본이 파일이라 git 이력이 없으므로 이 표가 유일한 변경 이력이다. 조회는 `GET /api/v1/admin/catalog/audit`.
- 코드: `CombinedCatalogCsv`(파서) · `CombinedCatalogStore`(CRUD·되쓰기) · `CombinedCatalogLoader`(시작 적재) · `CatalogAdminController` · `CatalogAuditLog`(감사) · `CatalogChangeRequests`(제안·승인) · `CatalogProposalReview`(검토 판정) · `SmartChoicePriceOracle`·`AiGateway`(두 소스).

## 1-C. 제휴 혜택을 어디서 얻는가 (2026-09-16)

**스마트초이스 크롤링은 하지 않는다.** 저작권 보호 정책이 "단체에서 내부적으로 이용하기 위해 복제하는
경우에는 그 회사가 영리회사가 아니더라도 복제가 불허"되고 "전체 내용의 10% 이상 인용 시 저작권 침해"라고
명시한다(https://www.smartchoice.or.kr/smc/etc/rightsRule.do, 2026-09-16 확인). 표 전체를 CSV로 옮겨
배포하는 것은 이 조건에 어긋난다. 이용이 필요하면 smartchoice@ktoa.or.kr 에 사전 허락을 받아야 한다.

대신 **요금제 매트릭스의 공식 표기**에서 읽는다 — `scripts/catalog_benefits_from_matrix.py`.
통신사 공식 페이지의 요금제명이 근거다: "베스트 Max(넷플릭스)", "모두다 맘껏 11GB+(웨이브 광고형)".

| 근거 | 처리 | 계산 |
|---|---|---|
| 요금제명에 서비스 + **등급**("웨이브 광고형") | `FREE` + 해당 `tier_id` | ✅ 반영 |
| **공식 상세의 혜택 표**에서 등급·금액 확정 | `FREE` 또는 `FIXED_DISCOUNT` | ✅ 반영 |
| 서비스만("넷플릭스"), 상세 미확인 | `BUNDLE_INCLUDED` + `tier_id` 비움 | ❌ 금액 효과 0, 표시만 |

**등급을 모르면 금액을 만들지 않는다.** `tier_id`를 비우면 그 서비스의 모든 등급에 매칭되므로
(`PlanBenefit.matches`), `FREE`로 넣으면 프리미엄 등급까지 0원이 되어 **실제보다 싸게 추천**한다.

### 공식 상세로 확정하기 (SKT, 2026-09-16)

요금제명만으로는 등급도 금액도 모른다. SKT 상품 상세의 **구독 혜택 표**가 둘 다 준다 —
`scripts/fetch_skt_ott_benefits.py`가 1회 수집한다(요청 간 1.2초, 런타임 호출 금지).

| 표 형식 | 읽는 값 | 결과 |
|---|---|---|
| 요금제 · T우주 상품 · 기본 혜택 · 추가 혜택 · **최대 할인 금액** | 등급 문구 + 할인액 | `FIXED_DISCOUNT` |
| 요금제 · **기본제공 멤버십** · **고객 부담금** · 최대 할인 | 등급 + 부담금 | 부담금 0이면 `FREE`, 아니면 `FIXED_DISCOUNT`(정가−부담금) |

확정한 값은 공식 문구와 교차 검증된다 — 베스트 109(넷플릭스)는 스탠다드 13,500 − 12,500 = **1,000원**이고
상세 문구가 "월 1,000원부터 이용 가능"이다. 89(넷플릭스)는 7,000 − 3,500 = 3,500원, 문구도 "3,500원부터"다.

**확정하지 않는 것**: ①"프리미엄 **또는** 스탠다드 **또는** 광고형"처럼 사용자가 등급을 고르는 상품
(베스트 Max·Pro) ②"티빙&웨이브"처럼 **두 서비스 묶음** — 우리 카탈로그에 묶음 상품이 없어 할인액을
한쪽에 붙이면 과대 할인이 된다. 이 32건은 `BUNDLE_INCLUDED`로 남는다.

한계: SKT 혜택은 **T 우주 구독 상품 가입**이 전제이고 휴대폰 요금과 **별도 청구**된다. 현재
`plan_benefit`에는 이 조건을 적을 칸이 없다(`note` 컬럼 없음). 금액은 맞지만 가입 절차는 화면이 안내해야 한다.

## 1-A. 검수와 변경

`python3 scripts/catalog_csv.py init/prepare/publish`로 작업 사본·검수 해시·변경 사유·출처 이용 근거를 남긴다.
정수 금액·단위·참조 관계·확인일·출처를 검증하고, 운영자가 원자료와 숫자를 대조한 승인 파일로만 발행한다.
추가·수정은 CSV 행을 변경하고 삭제는 행 제외 후 명시적 삭제 옵션으로 발행한다.
`CATALOG_CSV_DIR`을 설정한 앱은 60초마다 새 버전을 확인하고 전체 CSV를 한 DB 트랜잭션으로 반영한다.
검증/DB 반영 실패는 기존 정상 DB를 유지한다. 회원이 참조한 삭제 상품은 active=false로 남긴다.

AI 후보의 confidence나 두 모델의 같은 답만으로 정확성을 확정하지 않는다. 임의 크롤링·접근 제한 우회는 하지 않는다.
검증 전 후보는 계산/추천에서 제외한다. `OFFICIAL`은 파일 형식이 아니라 확인한 원자료의 성격에 따라 정한다.
상세 실행법·제보 처리·알려진 제한: [catalog-data.md](catalog-data.md).

## 2. 외부 API의 현재 상태

과거 우체국 키 미설정과 스마트초이스 성공 데이터 미확보를 확인했고 사용자 지시에 따라 연동을 제거했다.
9/16 재조사에서 스마트초이스는 **로컬 키 한 문자 누락**이 인증 거부 원인이었고, 발급 키로 100·8건을 두 번 받았다.
잘못된 기본 경로·실제 XML 태그와 파서의 불일치·진단 성공 오판도 확인했다. [원인 검증과 20개 대조 요청](smartchoice-integration-findings.md).
후속 결정으로 `SmartChoiceClient`·`SmartChoiceRecommendation`·테스트가 복구됐고 로컬 키도 교정됐다.
9/16 15시 검증에서 조건별 API 5회 모두 100·8~9건, Java 어댑터 실호출 8건·단위 테스트 6개 통과.
LTE/5G 30GB의 주요 반환 필드는 동일해 망 구분 정책은 추가 확인 대상이다. 최신 가격/약관 정합까지 검증한 것은 아니다.
`SmartChoiceSweepService`·`SmartChoiceSnapshotReader`·`PriceCrossCheck`는 없고 추천/배치 호출자도 없어 자동 교차검증은 미연결이다.
`PostOfficeMvnoClient`·`MvnoCatalogLoader`·과거 셸 진단은 제거 상태를 유지한다.
V7은 이미 적용된 Flyway 이력으로 유지하고 V9에서 사용하지 않는 시세 테이블을 제거한다.
이전 검증 기록은 worklog와 D-12~D-17에 남기며, 현재 카탈로그 운영 절차로 해석하지 않는다.

## 3. 제휴 혜택 자료 (D3) — 과거 수집안

D-18 이후 아래는 과거 자료의 출처/형식 참고다. 신규 수집은 이용 조건 확인 후 허용된 자료를 입력하며 이 절차를 자동 실행하지 않는다.

과거 대상: `https://www.smartchoice.or.kr/smc/plan/ottPdt.do`
OTT 아이콘(넷플·티빙·웨이브·디즈니+·유튜브프리미엄) 선택 시 AJAX로 두 표가 로드된다.

| 표 | 컬럼 → 매핑 |
|---|---|
| 이동전화 요금제 | 통신사/요금제/월정액/OTT 제공 혜택 → `plan_benefit` |
| 부가서비스 상품 | 통신사/구분/상품명/월정액/제공혜택 → `plan_benefit` |

절차: 개발자도구 Network에서 엔드포인트 확인 → OTT 5종 1회씩 (**요청 간 1초 이상**)
→ `db/seed/plan_benefit.csv` 커밋 → 크롤러는 `scripts/`에 두되 런타임 호출 금지.

준수: 비상업 교육 목적 한정 · 화면·발표에 **"출처: 스마트초이스(KTOA)"** 명시 ·
**실시간 크롤링 금지**(데모 중 구조 변경 시 발표 중단) · `docker compose up`만으로 시드 복원.

## 4. 결제 내역 (D8) — 5개 경로

| 옵션 | 난이도 | 품질 | 임팩트 | 판정 |
|---|---|---|---|---|
| A. Mock 마이데이터 (표준 형식) | 하 | 가짜 | 중 | ✅ 데모 기본 |
| B. **결제 영수증 이메일 (.eml)** | 중 | **최상** | 상 | ✅ **1순위** |
| C. 카드 명세서 PDF | 중 | 상 | 상 | 🟡 여유 시 |
| D. 거래내역 CSV | 하 | 중 | 중 | 🟡 여유 시 |
| E. 수기 입력 | 하 | 하 | 하 | ✅ 항상 제공 |
| ✗ Gmail API 연동 | 상 | 최상 | 상 | ❌ OAuth 검증이 마감 초과 |

### B가 1순위인 이유

| 얻는 것 | 카드 명세서 | 영수증 메일 |
|---|---|---|
| 서비스 식별 | `NETFLIX.COM` → 정규화 필요 | 정확한 서비스명 |
| **티어 식별** | ❌ 금액 추정 | ✅ "스탠다드" 명시 |
| **다음 결제일** | ❌ | ✅ 명시 |

다음 결제일이 있으면 Phase 2 "프로모션 종료 알림"이 사용자 입력 없이 성립한다.
티어 식별은 `TIER_DUPLICATE` 정확도를 올린다.

사용자 여정: Gmail 검색 → 메일 선택 → `.eml` 다운로드 → 업로드.
번거롭지만 본인전송요구권 범위 안이고, 데모에서는 미리 준비한 파일을 쓴다.

구현 순서: **A → B → E**. C·D는 안 해도 된다.

## 5. 마이데이터 판정

> 라이브 연동을 왜 안 넣었는지 상세 근거·대안·전환 경로: `docs/mydata.md`.

### ❌ 불가 — 제3자 전송
개인정보 마이데이터는 전문기관 지정 또는 일반수신자 등록, 금융 마이데이터는
본인신용정보관리업 허가가 필요하다. 법인이 아닌 팀으로는 불가능하다. **시도하지 않는다.**

### ✅ 채택 — 본인전송
2026년 2월 개인정보 보호법 시행령 개정으로 8월부터 시행되어, 일정 규모 이상 기업·기관의
홈페이지에서 조회되는 정보를 **개인이 직접 내려받아 관리**하는 것이 가능해졌다.
사용자 다운로드 → 요고비 업로드는 제도적 근거가 있다.

전문기관 지정 시 `PaymentHistoryProvider` 구현체 하나만 추가하면 전환된다.

## 6. Mock 마이데이터 (표준 형식 모방)

금융 마이데이터 표준 API의 카드 승인내역 응답을 모방한다.
실제 연동 시에는 금융보안원 표준 규격서로 필드명을 재확인한다.
(프로젝트 내부 표준으로 고정. 어긋나도 파서만 교체하면 된다.)

```json
{
  "rsp_code": "00000", "search_timestamp": "20260907120000",
  "approved_cnt": 1,
  "approved_list": [{
    "approved_num": "20260901093000001", "card_id": "CARD_001",
    "approved_dtime": "20260901093000", "approved_amt": 13500,
    "currency_code": "KRW", "merchant_name": "NETFLIX.COM",
    "merchant_regno": "1234567890", "status": "01", "paid_type": "01"
  }]
}
```
`status` `01`승인/`02`취소 · `paid_type` `01`일시불/`02`할부

### 데모 시나리오 3종 (`db/seed/mock_mydata/`)

| # | 상황 | 규칙 | 절감 |
|---|---|---|---|
| 1 | 요금제에 웨이브 포함인데 별도 결제 | `BENEFIT_OVERLAP` | 월 10,900 |
| 2 | 티빙·웨이브 개별 결제 (더블 미가입) | `BUNDLE_OVERLAP` | 월 9,400 |
| 3 | 넷플 스탠다드+프리미엄 중복 | `TIER_DUPLICATE` | 월 13,500 |

### 가맹점명 정규화 (`merchant_alias`)

| service_id | pattern | match_type |
|---|---|---|
| 1 | `NETFLIX` / `넷플릭스` | `CONTAINS` |
| 4 | `WAVVE` / `콘텐츠웨이브` | `CONTAINS` |
| 6 | `GOOGLE *YOUTUBE` | `PREFIX` |

매칭 실패 시 `service_id`를 `null`로 두고 사용자에게 묻는다.
**추측 매핑 금지.** 잘못된 매핑은 잘못된 절감액으로 이어진다.

## 7. 시드 수집 규칙 (팀원 공지용)

`db/seed/mobile_plan_TEMPLATE.csv` 형식.

| 컬럼 | 필수 | 비고 |
|---|---|---|
| `carrier` | ✅ | SKT / KT / LGU+ / MVNO명 |
| `plan_name` | ✅ | 공식 표기 그대로 |
| `network_type` | ✅ | 5G / LTE / 3G |
| `base_price` | ✅ | **VAT 포함** 정수(원) |
| `data_mb` `voice_min` `sms_cnt` | ✅ | 무제한은 `999999` |
| `contract_discount_12m` `_24m` | | 요금제 고유 약정할인 |
| `source_url` | ✅ | **없으면 병합하지 않는다** |
| `collected_at` | ✅ | YYYY-MM-DD |

- VAT 별도 표기면 ×1.1 후 반올림
- 한시 프로모션 할인은 `base_price`에 반영하지 않는다
- 무제한은 숫자 `999999`. "무제한" 문자열이나 빈칸 금지

분담: SKT 2명 · KT 2명 · LGU+ 2명 · 알뜰폰 1명 (각 60건 / 30건)

## 8. 사용량 입력 안내 (D6)

입력 필드 옆에 그대로 노출한다.

| 통신사 | 경로 |
|---|---|
| SKT | My > 나의 요금 > 사용 요금 내역 > 사용패턴 |
| KT | 마이 > 가입/이용 > 이용량/이용내역 > 이용량 조회 |
| LG U+ | 마이페이지 > 사용 현황 > 사용내역 조회 > 월별사용량조회 |

최근 3개월 평균을 입력하도록 안내한다.
**선택 기능**: 사용량 화면 스크린샷 → AI 서버 OCR. 추출값은 `ESTIMATED`로 표기하고
사용자 확인 후 계산에 넣는다.

## 9. 시드 현황

| 파일 | 상태 |
|---|---|
| `subscription_service.csv` | ✅ 6건 |
| `subscription_tier.csv` | ✅ 17건 |
| `bundle_product.csv` | ✅ 7건 |
| `plan_benefit.csv` | ⬜ D3 크롤링 |
| `mobile_plan.csv` | ⬜ 팀원 수집 |
| `merchant_alias.csv` `mock_mydata/` | ⬜ |

출처: 스마트초이스(KTOA), 2026-09-07 기준.
