# 요고비 백엔드 (BE_main)

통신 요금제 + 구독 서비스(OTT 등)의 **실제 지불 총액**을 계산하고, 더 싼 조합과
변경 시점을 추천한다. 금액 계산의 유일한 주체다.

Java 21 · Spring Boot · PostgreSQL. AI 서버는 별도 레포: github.com/yogoB/AI-

---

## 절대 원칙

1. **미사용 혜택은 0원.**
   사용자가 원하지 않는 서비스의 제휴 혜택은 계산에도, 추천 근거에도 쓰지 않는다.
   "넷플릭스 무료 제공"은 사용자가 넷플릭스를 원할 때만 가치를 갖는다.

2. **금액 계산은 `pricing` 모듈만.** AI 서버는 숫자를 만들지 않는다.

3. **필터 경로와 챗봇 경로는 같은 엔드포인트를 호출한다.**
   `POST /api/v1/recommendations` 하나뿐이다.

4. **모든 금액에 출처(Provenance)를 붙인다.**
   `OFFICIAL` / `DERIVED` / `USER_PROVIDED` / `ESTIMATED`.

5. **사용자의 불편함을 덜어주는 웹앱.** 사용성은 취향이 아니라 검수 항목이다 (D-14).
   "쓰기 편하게"는 검증할 수 없으므로 아래 다섯 줄로만 판정한다.
   - **모르면 막히는 화면을 만들지 않는다.** 모든 필수 입력에 "모름/건너뛰기" 경로가 있고,
     그 경로로도 결과까지 간다. 서버 절반은 이미 있다 — `MissingInput(field, impact, howToFind)`.
   - **사용자가 아는 것만 묻는다.** 금액은 우리가 채우고(`ESTIMATED`) 사용자는 고치기만 한다
     (고치면 `USER_PROVIDED`로 승격). 빈 입력창을 사용자에게 떠넘기지 않는다.
   - **결론을 먼저 낸다.** 목록·표보다 금액 한 문장이 위에 온다. 절감액은 월·연을 같이 적는다.
   - **근거는 접되 버리지 않는다.** 기본은 금액만, 펼치면 계산 과정과 Provenance.
     원칙 4를 지키면서 화면을 가볍게 유지하는 유일한 방법이다.
   - **같은 숫자는 같은 컴포넌트로.** 필터·챗봇·계산기가 결과 카드 하나를 공유한다.
     원칙 3의 화면판 — 엔드포인트가 하나면 카드도 하나다.

---

## 작업 전에 읽을 문서

| 상황 | 문서 |
|---|---|
| **화면·응답의 UX 원칙 (공통)** | `../UX_POLICY.md` — 절대원칙 #5의 상위 정책. 겹치면 #5가 우선 |
| 세션 시작 | `docs/state.md` |
| **계산 코드를 쓰기 전** | `docs/testing.md` — 골든 케이스가 정답이다 |
| **이름을 지을 때** | `docs/domain.md` §2 — 지어내지 않는다 |
| 계산 규칙 | `docs/domain.md` |
| API·모듈·스키마·규약 | `docs/architecture.md` |
| 데이터 수집·시드 | `docs/data.md` |
| 일정·결정 이력 | `docs/project.md` |
| 개발 이력·Obsidian 동기화 | `docs/knowledge.md` |

라우팅 표에 없는 내용을 추측으로 채우지 않는다. 모르면 묻는다.

---

## 코딩 규칙

### 코드를 쓰기 전 — `/karpathy-guidelines`

백엔드 코드를 **쓰거나 고치거나 리뷰하기 전에** 이 스킬을 부른다. 예외: 오타·문구 수정 같은 한 줄짜리.

- **성공 기준을 먼저 적는다** (스킬 §4). "검증 추가"가 아니라 "잘못된 입력 테스트를 쓰고 통과시킨다".
  이 레포는 이미 그렇게 한다 — `docs/testing.md`에 골든 케이스를 먼저 적고 구현한다.
- **가정은 숨기지 말고 드러낸다** (§1). 해석이 갈리면 고르지 말고 묻는다.
- §2 단순성·§3 수술적 변경은 ponytail과 겹친다. 겹치는 부분은 한 번만 적용하면 된다.

**기록**: 스킬을 쓴 작업은 `docs/worklog.md`에 **성공 기준과 그 검증 결과**를 남긴다.
"테스트 통과"가 아니라 "무엇이 통과를 정의했고 실제로 무엇을 돌렸는지"를 적는다.
출처·버전은 `.claude/skills/karpathy-guidelines/SOURCE.md`.

### 의존 방향 (단방향, 위반 금지)

```
user / subscription / detection / recommend / chat / alert
                        ↓
                     pricing        ← 아무것도 의존하지 않는다
                        ↓
                     common
```

- `pricing`에 Spring 애노테이션(`@Service` `@Component` `@Transactional`),
  JPA 엔티티, Repository를 **넣지 않는다.** 순수 자바 도메인이다.
- `pricing`이 외부 정보를 알아야 하면 `PricingContext`에 담아 인자로 넘긴다.

### 금액

- `long` 원 단위 또는 `Money` 값 객체. **`double`/`float` 금지.**
- 정률 할인은 `BigDecimal`로 계산하고 원 단위 **내림(floor)**.
- 총액만 반환하지 않는다. 항목별 `CostBreakdown`을 반환한다.

### 이름

- `docs/domain.md` §2 표에서 찾는다. 없으면 표에 추가하고 커밋한다.
- **`Plan`이라는 단독 식별자는 어디에도 쓰지 않는다.**
  통신 요금제(`MobilePlan`)와 OTT 요금제(`SubscriptionTier`)가 충돌한다.

### 테스트

- 새 `DiscountRule`은 `docs/testing.md`에 골든 케이스를 **먼저 적고** 구현한다.
- `pricing`·`detection` 은 **분기 커버리지 100%가 강제된다** — `./gradlew check` 가 미달 시 실패한다(2026-09-17).
  기준 숫자의 출처는 `docs/testing.md` "기준" 절이고 `build.gradle` 은 그것을 지키는 장치다.
- 골든 케이스에는 **누가 검증하는지를 적는다.** `python3 scripts/golden_audit.py --check` 가 침묵을 잡는다.
  "없음 — 프론트 로직" 처럼 없다고 적는 것도 정답이다. 금지하는 것은 아무 말도 안 하는 것이다.

---

## 코드 탐색

파일을 grep하기 전에 지식 그래프를 먼저 질의한다 (설치되어 있다면).

```
/graphify query "..."          구조 파악
/graphify explain "<식별자>"    개념과 이웃
/graphify path "<A>" "<B>"     영향 범위
```

`INFERRED` 태그가 붙은 엣지는 파일을 열어 확인하기 전까지 근거로 삼지 않는다.

---

## 스코프 밖 — 제안도 하지 말 것

- **수익 모델(BM)** — 범위에서 명시적으로 제외 (`docs/project.md` D-01)
- **마이데이터 제3자 전송 연동** — 전문기관 지정 필요, 불가능 (D-04)
- **런타임 크롤링** — 데이터는 시드 스냅샷으로 고정 (D-05)
  - D-18: 이용 조건을 확인한 자료의 AI 추출·검증 후 CSV 반영만 허용한다(`docs/catalog-data.md`).
    CSV가 카탈로그 원본이며 요청 중 외부 가격 조회는 하지 않는다. 우체국 연동은 제거한다. 스마트초이스는 검증 오버레이로 되살림(D-20): 카탈로그 소스 아님, 배치 스냅샷 대조로만 쓴다.
- **CI/CD, 쿠버네티스, 마이크로서비스 분리, 캐시 레이어** — 3주 프로젝트다

---

## 실행

```bash
cp .env.example .env
docker compose up -d
./gradlew test
./gradlew bootRun
```

## 세션 규칙

- 작업이 끝나면 `docs/state.md`의 "현재 상태 / 다음 할 일"을 갱신한다.
- 작업 과정·검증 결과는 `docs/worklog.md`에 기록한다. 문서 변경 후 `/graphify --update`로 의미 관계를 갱신하고
  `python3 scripts/knowledge.py sync`로 Obsidian에 반영한다. 실패하면 동기화했다고 보고하지 않는다.
- 커밋 author·메시지에 AI 귀속 흔적을 남기지 않는다. author 는 `winwinhun` 만 쓰고
  `Co-Authored-By` 트레일러나 에이전트 식별 태그를 붙이지 않는다.
- `docs/architecture.md` §3 API 계약 표는 **사람이 관리한다.**
  변경이 필요하면 먼저 제안하고 승인받는다. AI 서버 레포의 사본도 같이 맞춰야 한다.
