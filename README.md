# 요고비 백엔드 (BE_main)

통신 요금제 + 구독 서비스의 **실제 지불 총액**을 계산해 더 싼 조합과 변경 시점을 추천한다.

> **안 쓰는 혜택은 0원이다.**
> 넷플릭스를 안 보는 사람에게 "넷플릭스 무료"를 이유로 비싼 요금제를 추천하지 않는다.

Java 21 · Spring Boot · PostgreSQL · AI 서버: [yogoB/AI-](https://github.com/yogoB/AI-)

```bash
# Java 21 JDK와 실행 중인 Docker가 필요하다.
cp .env.example .env
docker compose up -d --wait
./gradlew test
./gradlew bootRun
```

Gradle Wrapper 8.14.4 · Spring Boot 3.5.16을 사용한다. `JAVA_HOME`은 Java 21 JDK로 설정한다.
5432 포트를 이미 사용 중이면 `.env`의 `POSTGRES_PORT`를 바꾼다.
Compose는 PostgreSQL만 실행하며, 앱은 `.env`를 직접 읽고 기본 8080 포트에서 실행한다.

앱 기동 시 Flyway가 `db/migration/`을 적용한 뒤 서비스 6건·티어 17건·번들 7건과
번들 구성 15건을 CSV에서 적재한다. CSV와 마이그레이션은 JAR에도 포함된다.
추천·계산기·카탈로그 HTTP API와 챗봇 게이트웨이가 구현되어 있다.

## 챗봇 게이트웨이

회원 기능은 [회원 인증 명세](docs/auth.md)를 참고한다. 자체 가입·로그인과 Google OIDC를 제공하며,
비회원 추천은 그대로 사용할 수 있다. 회원 쿠키 발급에는 독립 `JWT_SECRET` 설정이 필요하다.
Google은 OAuth 클라이언트 설정 후 활성화한다. [보안 시나리오 검증](docs/auth-security.md)에 검증 범위와 한계를 기록했다.

`POST /api/v1/chat/messages`에 `{"text":"데이터 20기가 쓰고 넷플릭스 보고 싶어요"}`를 보낸다.
메시지는 공백을 제외한 내용이 있어야 하며 최대 4,000자다.
BE가 `AI_SERVER_URL`(기본 `http://localhost:8000`)의 `/parse`를 호출하고,
필터 API와 같은 `RecommendationService`로 추천한 뒤 첫 번째 결과를 `/narrate`로 설명한다.
금액과 추천 순서는 기존 계산·추천 엔진이 정한다.
프론트는 BE API만 호출한다. AI 서버의 `/parse`·`/narrate`·`/ocr`는 서버 간 내부 API다.
두 서버에 동일한 `AI_INTERNAL_TOKEN`을 비밀 환경 변수로 설정하면 BE가
`Authorization: Bearer <AI_INTERNAL_TOKEN>`을 붙인다. 프론트에서 받은 인증 헤더는 전달하지 않는다.
AI 주소·내부 토큰·모델 키는 프론트 설정에 넣지 않는다. 배포 시 AI에는 사설 주소 또는 BE만 허용한 네트워크를 사용한다.
내부 토큰이 없거나 일치하지 않으면 챗봇은 필터로 안내한다. 필터·계산기·카탈로그는 그대로 동작한다.

응답은 `{"data":{"status":"RECOMMENDED","message":"...","recommendation":{...}},"warnings":[]}`다.
`recommendation`은 필터 API의 `data`와 같은 `accuracy`, `missingInputs`, `results` 구조다.

| status | 처리 |
|---|---|
| `RECOMMENDED` | 추천 전체 목록과 첫 결과 설명 반환. 설명 실패 시 목록을 보존하고 `YGB-EXT-001` 경고 |
| `NEEDS_INPUT` | 낮은 confidence 또는 `AI-PARSE-001`. 고정 확인 질문, `recommendation: null` |
| `FILTER_FALLBACK` | AI 장애·응답 검증 실패. 필터 입력 안내, `recommendation: null`, `YGB-EXT-001` 경고 |

AI 실패는 HTTP 200과 위 상태로 안내하며, 필터 API는 계속 사용할 수 있다.
잘못된 메시지는 400 `YGB-REQ-001`, 추천 후보가 없으면 기존과 동일하게 422 `YGB-CAL-001`이다.
AI 연결은 HTTP/1.1, 연결 대기 5초·응답 대기 25초이며 자동 재시도하지 않는다.
현재 게이트웨이는 한 발화만 처리한다. 추가 답변에도 추천 조건을 함께 보내야 하며,
사용자별 대화 이력 저장·조건 병합은 아직 연결하지 않았다.

검증: 백엔드 60개 테스트 통과. 별도 PostgreSQL·개발 시드·실제 AI HTTP 서버로
정상 추천, 되묻기, 모델 장애 시 필터 폴백을 검증했다(모델 응답만 스텁).

테스트는 Testcontainers의 별도 PostgreSQL에서 초기 복원·재로딩·CSV 오류 시 롤백을 검증한다.
로컬 개발 DB는 건드리지 않는다. 실행 JAR은 `./gradlew bootJar`로 만든다.

개발 DB를 완전히 새로 만들 때만 아래를 실행한다 (**해당 Compose DB의 데이터가 삭제된다**).

```bash
docker compose down -v
docker compose up -d --wait
./gradlew bootRun
```

## 문서

| 문서 | 내용 |
|---|---|
| [`AGENTS.md`](AGENTS.md) | 절대 원칙·코딩 규칙 — AI 에이전트가 자동 로드 |
| [`docs/domain.md`](docs/domain.md) | 계산 규칙 + 용어↔코드 식별자 |
| [`docs/testing.md`](docs/testing.md) | 골든 케이스 — **계산 코드보다 먼저 읽는다** |
| [`docs/architecture.md`](docs/architecture.md) | 패키지·API 계약·스키마·개발 규약 |
| [`docs/data.md`](docs/data.md) | 데이터 소스·시드 수집 규칙 |
| [`docs/project.md`](docs/project.md) | 일정 + 결정 이력(ADR) |
| [`docs/state.md`](docs/state.md) | 현재 진행 상황 |
| [`docs/knowledge.md`](docs/knowledge.md) | graphify·Obsidian 개발 기록 동기화 |
| [`docs/worklog.md`](docs/worklog.md) | 날짜별 작업·검증 기록 (Obsidian 자동 반영) |

## 기획자 팀원이 할 일

1. [`docs/domain.md`](docs/domain.md) §1을 읽는다 — 용어를 팀 전체가 같게 쓰는 게 중요하다.
2. [`docs/data.md`](docs/data.md) §7 수집 규칙대로 `db/seed/mobile_plan_TEMPLATE.csv`를 채운다.

데이터 출처: **스마트초이스(한국통신사업자연합회, KTOA)** · 비상업 교육 목적
