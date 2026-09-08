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

---

## 작업 전에 읽을 문서

| 상황 | 문서 |
|---|---|
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
- `pricing`은 분기 커버리지 100%가 목표다.

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
