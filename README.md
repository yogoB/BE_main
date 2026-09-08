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
현재 추천·카탈로그 HTTP API는 아직 구현되지 않았다.

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
