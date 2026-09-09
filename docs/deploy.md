# 배포 (Fly.io)

스마트초이스 Open API 발급에 필요한 **서비스 URL**을 빠르게 확보하고, 데모를 띄우기 위한 최소 배포.
나중에 도메인을 사면 DNS만 붙이면 되고(아래 §7), 이 `*.fly.dev` URL·API 키는 그대로 유효하다.

> 스코프 주의: 이건 데모용 단일 배포다. CI/CD·k8s·오토스케일은 범위 밖(`AGENTS.md`).

---

## 0. 왜 Fly.io로 흐름이 되는가 (한눈에)

```
로컬 코드 ──(Dockerfile)──> Fly 빌더가 이미지 생성 ──> Fly 머신에서 실행
                                                        │
        fly postgres (같은 사설망) ─── DATABASE ────────┘
   앱 기동 시: Flyway V1·V2 자동 마이그레이션 → CatalogSeedLoader 가 jar 안의 시드 CSV 적재
   결과 URL: https://<app>.fly.dev
```

앱은 이미 배포 친화적이다: 설정은 전부 환경변수(`POSTGRES_*`)에서 오고(`application.properties`의
`${POSTGRES_HOST:...}` 자리표시자), `.env`는 `optional:`이라 없어도 된다. Flyway가 스키마를,
`CatalogSeedLoader`가 시드를 기동 시 자동 처리한다.

---

## 1. 사전 준비

- Fly 계정 + **결제수단 등록**(무료 체험 크레딧이 있어도 카드가 필요하다). 소액 과금 가능.
- flyctl 설치: `brew install flyctl` (mac) → `fly auth login`
- 이 레포에 이미 있는 파일: `Dockerfile`, `.dockerignore`, `fly.toml` (아래 절차는 이걸 재사용한다)

---

## 2. 필요한 작업 (코드/설정)

이미 만들어 둔 것:

| 파일 | 역할 |
|---|---|
| `Dockerfile` | 멀티스테이지(JDK 빌드 → JRE 실행). `bootJar`만 만들고 테스트는 스킵 |
| `.dockerignore` | 빌드 컨텍스트 축소·비밀값 차단. `db/seed/dev/`는 데모 시드용으로 일부러 남김 |
| `fly.toml` | 포트 8080, HTTPS 강제, 헬스체크(`/api/v1/catalog/services`), 머신 512MB |

손볼 수 있는 것:
- `fly.toml`의 `app` 이름이 전역에서 이미 쓰이면 다른 이름으로 바꾼다.
- 메모리 512MB가 빠듯하면(OOM) `[[vm]] memory = "1024mb"`로 올린다.
- (선택) 제대로 된 헬스 엔드포인트를 원하면 `spring-boot-starter-actuator`를 추가하고 헬스체크 path를
  `/actuator/health`로 바꾼다. 지금은 의존성 없이 카탈로그 GET으로 대체한다.

---

## 3. 앱·DB 프로비저닝

```bash
# (1) 앱 생성 — fly.toml 을 재사용한다. 지금은 배포하지 않는다.
fly launch --no-deploy --copy-config --name yogobi-be --region nrt

# (2) PostgreSQL 생성 (Fly Postgres, 사설망 안에서 앱과 연결됨)
fly postgres create --name yogobi-db --region nrt --initial-cluster-size 1 --vm-size shared-cpu-1x --volume-size 1

# (3) 앱에 DB 연결 → DATABASE_URL 이 secret 으로 주입되고, 접속 정보가 출력된다
fly postgres attach yogobi-db --app yogobi-be
```

`attach` 출력의 `postgres://<user>:<password>@<host>:5432/<db>` 에서 값을 뽑아,
앱이 읽는 `POSTGRES_*` 형태로 secret 을 세팅한다(우리 `application.properties`가 이 이름들을 쓴다):

```bash
fly secrets set --app yogobi-be \
  POSTGRES_HOST=yogobi-db.internal \
  POSTGRES_PORT=5432 \
  POSTGRES_DB=<attach가 알려준 db> \
  POSTGRES_USER=<attach가 알려준 user> \
  POSTGRES_PASSWORD=<attach가 알려준 password>
```

> 대안: 코드 수정 없이 `SPRING_DATASOURCE_URL=jdbc:postgresql://yogobi-db.internal:5432/<db>` +
> `SPRING_DATASOURCE_USERNAME` + `SPRING_DATASOURCE_PASSWORD` secret 으로 줘도 된다(Spring 완화 바인딩).

---

## 4. 배포

```bash
fly deploy --app yogobi-be
```

기동 시 자동으로:
1. Flyway가 `V1`(카탈로그 8테이블)·`V2`(P1 테이블) 마이그레이션 적용
2. `CatalogSeedLoader`가 jar 안의 서비스·티어·번들 CSV 적재
   (실제 `mobile_plan.csv`·`plan_benefit.csv`는 아직 없으므로 요금제/혜택은 비어 있음)

**데모로 실제 추천 결과까지 보이고 싶다면** 더미 시드를 켠다(D3/D4 실데이터 전까지):

```bash
fly secrets set --app yogobi-be SPRING_PROFILES_ACTIVE=dev
fly deploy --app yogobi-be
```
→ `DevSeedLoader`가 `db/seed/dev/`의 더미 요금제 5·혜택 6을 적재해 recommendations가 결과를 낸다.

---

## 5. 배포 후 확인

```bash
fly open --app yogobi-be                      # 브라우저로 https://yogobi-be.fly.dev
curl https://yogobi-be.fly.dev/api/v1/catalog/services   # 200 + 서비스 6건
fly logs --app yogobi-be                       # 기동·마이그레이션·시드 로그
fly status --app yogobi-be
```

`/api/v1/catalog/services`가 200이면 앱·DB·마이그레이션·시드까지 정상이다.

---

## 6. 스마트초이스 신청과의 관계

- 신청 폼의 "웹 주소"에 `https://yogobi-be.fly.dev`(또는 프론트 URL)를 적으면 된다.
- 이 API는 **백엔드가 `authkey`로 서버 호출**하는 방식이라(`docs/data.md §2`) 도메인에 묶이지 않는다.
  나중에 도메인을 바꿔도 키는 그대로 동작한다. 폼 URL은 행정용이라 대개 포털에서 수정 가능.
- 키는 **교차검증 전용 + fail-soft**다. 승인이 늦거나 URL이 바뀌어도 추천/배포는 안 막힌다.

---

## 7. 나중에 도메인 붙이기 (DNS만 교체)

도메인을 사면, 앱을 재배포하지 않고 별칭만 추가한다:

```bash
fly certs add api.yourdomain.com --app yogobi-be
fly certs show api.yourdomain.com --app yogobi-be   # 넣어야 할 DNS 레코드 확인
```

출력대로 도메인 등록업체에서 DNS 설정:
- 서브도메인(`api.`) → **CNAME** → `yogobi-be.fly.dev`
- 루트 도메인 → Fly가 주는 **A/AAAA** 레코드

전파되면 인증서가 자동 발급되고 커스텀 도메인으로 접속된다. `*.fly.dev` URL도 계속 살아있다.

---

## 8. 운영 메모

- **비밀값**: `POSTGRES_*`·`JWT_SECRET`(인증 구현 후)·`SMARTCHOICE_API_KEY`는 `fly secrets`로만 넣는다.
  코드·`fly.toml`·git에 적지 않는다(`.dockerignore`가 `.env`를 차단).
- **비용 절감**: `auto_stop_machines="stop"` + `min_machines_running=0`이라 트래픽 없으면 머신이 멈춘다.
  첫 요청은 콜드 스타트로 몇 초 걸릴 수 있다.
- **마이그레이션**: 적용된 Flyway 파일은 수정 금지, 항상 새 `V{n}` 추가(`AGENTS.md`).
- **롤백**: `fly releases --app yogobi-be` → `fly deploy --image <이전 이미지>` 또는 `fly apps restart`.
