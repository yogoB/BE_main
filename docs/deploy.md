# 배포 (Fly.io)

데모를 띄우기 위한 최소 배포. 나중에 도메인을 사면 DNS만 붙이면 되고(아래 §7), 이 `*.fly.dev` URL은 그대로 유효하다.

> 스코프 주의: 이건 데모용 단일 배포다. CI/CD·k8s·오토스케일은 범위 밖(`AGENTS.md`).

## 앱이 셋이다 — 이름을 겹치지 마라 (2026-09-16 · AI 추가 2026-09-17)

| 앱 | 레포 | 역할 | 공개 |
|---|---|---|---|
| `yogob` | github.com/yogoB/Front | React 프론트 + **API 프록시**(nginx) | 공개 |
| **`yogob-api`** | 이 레포 | Spring BE | 공개 |
| `yogob-ai` | github.com/yogoB/AI- | 설명 문장·추천 사유 | **사설 전용** |

**브라우저에 노출되는 오리진은 `https://yogob.fly.dev` 하나다.** 프론트의 `nginx.conf`가
`/api`·`/oauth2`·`/login/oauth2`를 `yogob-api`로 넘긴다. 같은 오리진이므로 CORS 설정도,
교차 사이트 쿠키(현재 `SameSite=Lax`로는 불가)도 필요 없다.

⚠️ **두 레포의 `fly.toml` app 이름을 같게 두면 나중에 배포한 쪽이 앞선 배포를 덮어쓴다.**
2026-09-16에 실제로 프론트 배포가 BE를 덮어 API가 전부 404가 됐다. 이름 분리가 그 재발 방지책이다.

`yogob-api`에 설정한 값: `POSTGRES_*`(`yogob-db` attach), `JWT_SECRET`(**표준 Base64여야 한다** —
URL-safe 문자열을 넣으면 `JWT_SECRET must be Base64`로 기동에 실패한다), `AUTH_RETURN_URL`(프론트
`account.html`), `YOGOBI_CORS_ALLOWED_ORIGINS`(프록시라 사실상 미사용),
`AI_SERVER_URL`·`AI_INTERNAL_TOKEN`(아래 §3-2).

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
| `fly.toml` | 포트 8080, HTTPS 강제, 헬스체크(`/api/v1/catalog/services`), 머신 512MB, 지표 스크레이프(9091) |

손볼 수 있는 것:
- `fly.toml`의 `app` 이름이 전역에서 이미 쓰이면 다른 이름으로 바꾼다.
- 메모리 512MB가 빠듯하면(OOM) `[[vm]] memory = "1024mb"`로 올린다.
- 헬스체크 path 는 `/api/v1/catalog/services` 그대로다. 액추에이터는 들어왔지만(D-25) **관리 포트 9091**
  에만 떠서 공개 포트(8080)를 지나는 `http_service` 체크로는 닿지 않는다. 바꾸려면 포트를 지정할 수 있는
  최상위 `[checks]` 로 옮겨야 하는데, 지금 체크가 DB 연결까지 검증하며 잘 돌고 있어 그대로 둔다.

---

## 3. 앱·DB 프로비저닝

```bash
# (1) 앱 생성 — fly.toml 을 재사용한다. 지금은 배포하지 않는다.
fly launch --no-deploy --copy-config --name yogob-api --region nrt

# (2) PostgreSQL 생성 (Fly Postgres, 사설망 안에서 앱과 연결됨)
fly postgres create --name yogobi-db --region nrt --initial-cluster-size 1 --vm-size shared-cpu-1x --volume-size 1

# (3) 앱에 DB 연결 → DATABASE_URL 이 secret 으로 주입되고, 접속 정보가 출력된다
fly postgres attach yogobi-db --app yogob-api
```

`attach` 출력의 `postgres://<user>:<password>@<host>:5432/<db>` 에서 값을 뽑아,
앱이 읽는 `POSTGRES_*` 형태로 secret 을 세팅한다(우리 `application.properties`가 이 이름들을 쓴다):

```bash
fly secrets set --app yogob-api \
  POSTGRES_HOST=yogobi-db.internal \
  POSTGRES_PORT=5432 \
  POSTGRES_DB=<attach가 알려준 db> \
  POSTGRES_USER=<attach가 알려준 user> \
  POSTGRES_PASSWORD=<attach가 알려준 password>
```

> 대안: 코드 수정 없이 `SPRING_DATASOURCE_URL=jdbc:postgresql://yogobi-db.internal:5432/<db>` +
> `SPRING_DATASOURCE_USERNAME` + `SPRING_DATASOURCE_PASSWORD` secret 으로 줘도 된다(Spring 완화 바인딩).

### 3-2. AI 서버(`yogob-ai`) — 공개 IP 없이 (2026-09-17)

AI 서버는 **인터넷에 노출하지 않는다.** BE 만 사설망으로 부른다(`docs/architecture.md` §2).
공개 IP 를 할당하지 않고 **flycast**(Fly 프록시를 지나는 사설 IPv6) 하나만 둔다 —
`.internal` 은 프록시를 우회하므로 멈춘 머신을 깨우지 못해 `auto_stop` 과 같이 쓸 수 없다.

```bash
cd ../AI-
fly apps create yogob-ai --org personal
fly ips allocate-v6 --private -a yogob-ai     # 공개 IP 는 할당하지 않는다
fly deploy --ha=false
fly ips list -a yogob-ai                      # private ingress 한 줄만 나와야 한다
```

두 서버가 **같은 내부 토큰**을 공유한다. 한 번 만들어 양쪽에 같은 값을 넣는다(값은 출력하지 않는다):

```bash
TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
fly secrets set AI_INTERNAL_TOKEN="$TOKEN" -a yogob-ai
fly secrets set AI_INTERNAL_TOKEN="$TOKEN" AI_SERVER_URL=http://yogob-ai.flycast -a yogob-api
unset TOKEN
```

- 포트는 붙이지 않는다. flycast 서비스가 80 → 8000 으로 넘긴다(`force_https = false` — 사설망엔 TLS 종단이 없다).
- 순서가 중요하다: **AI 앱에 먼저** 넣는다. BE 가 먼저 주소를 알면 그동안 401 을 받아 설명이 빈다.
- 두 앱의 `fly secrets list` 에서 `AI_INTERNAL_TOKEN` 의 DIGEST 가 같아야 한다. 다르면 값이 어긋난 것이다.
- **`ANTHROPIC_API_KEY` 는 넣지 않아도 된다.** 설명 문장과 추천 사유는 결정론적 경로로 나온다(D-38·D-40).
  모델 키는 운영자 경로(`/catalog/candidates`·카탈로그 추출 배치)에만 필요하다.

확인:

```bash
fly logs -a yogob-ai --no-tail | grep 'POST /narrate'   # BE 호출이 찍힌다
curl -sS -X POST https://yogob-api.fly.dev/api/v1/recommendations \
  -H 'content-type: application/json' \
  -d '{"required":{"monthlyDataGb":20,"wantedServiceIds":[1]}}'
```
→ `data.message` 가 문장이고 `data.reasons` 가 비어 있지 않으면 연결된 것이다.
`warnings` 에 `YGB-EXT-001` 이 있으면 BE 가 AI 에 닿지 못한 것이다(추천 결과 자체는 정상).

**콜드 스타트 (2026-09-17 정정)**: BE(`yogob-api`)는 더 이상 멈추지 않는다 — `min_machines_running = 1`.
그 전의 "첫 요청만 느리다"는 **틀렸다**. Fly 프록시는 깨운 머신의 8080 을 8.3초만 기다리고 포기하는데
Spring Boot 기동은 12~13초라, 쉬다가 들어온 첫 요청은 느린 게 아니라 **실패했다**
(운영 로그 04:48:21 `Starting machine` → 04:48:30 `gave up after 15 attempts (in 8.31s)` → 04:48:36 `Tomcat started`).
Google 로그인이 이 경로를 매번 밟는다 — 동의 화면에 머무는 동안 BE 트래픽이 0 이라 머신이 멈추고,
다음 요청이 곧 OAuth 콜백이다. 재시작하면 인메모리 OAuth 임시 세션도 사라져 재시도마저 `#auth=failed` 다.
AI(`yogob-ai`)는 FastAPI 기동이 1초 남짓이라 프록시의 8초 안에 들어오므로 `min_machines_running = 0` 그대로 둔다.

---

## 4. 배포

```bash
fly deploy --app yogob-api
```

기동 시 자동으로:
1. Flyway가 `V1`(카탈로그 8테이블)·`V2`(P1 테이블) 마이그레이션 적용
2. `CatalogSeedLoader`가 jar 안의 서비스·티어·번들 CSV 적재
   (실제 `mobile_plan.csv`·`plan_benefit.csv`는 아직 없으므로 요금제/혜택은 비어 있음)

**데모로 실제 추천 결과까지 보이고 싶다면** 더미 시드를 켠다(D3/D4 실데이터 전까지):

```bash
fly secrets set --app yogob-api SPRING_PROFILES_ACTIVE=dev
fly deploy --app yogob-api
```
→ `DevSeedLoader`가 `db/seed/dev/`의 더미 요금제 5·혜택 6을 적재해 recommendations가 결과를 낸다.

---

## 5. 배포 후 확인

```bash
fly open --app yogob-api                      # 브라우저로 https://yogob-api.fly.dev
curl https://yogob-api.fly.dev/api/v1/catalog/services   # 200 + 서비스 6건
fly logs --app yogob-api                       # 기동·마이그레이션·시드 로그
fly status --app yogob-api
```

`/api/v1/catalog/services`가 200이면 앱·DB·마이그레이션·시드까지 정상이다.

---

## 6. 스마트초이스 신청과의 관계

- 신청 폼의 "웹 주소"에 `https://yogob-api.fly.dev`(또는 프론트 URL)를 적으면 된다.
- 이 API는 **백엔드가 `authkey`로 서버 호출**하는 방식이라(`docs/data.md §2`) 도메인에 묶이지 않는다.
  나중에 도메인을 바꿔도 키는 그대로 동작한다. 폼 URL은 행정용이라 대개 포털에서 수정 가능.
- 키는 **교차검증 전용 + fail-soft**다. 승인이 늦거나 URL이 바뀌어도 추천/배포는 안 막힌다.

---

## 7. 나중에 도메인 붙이기 (DNS만 교체)

도메인을 사면, 앱을 재배포하지 않고 별칭만 추가한다:

```bash
fly certs add api.yourdomain.com --app yogob-api
fly certs show api.yourdomain.com --app yogob-api   # 넣어야 할 DNS 레코드 확인
```

출력대로 도메인 등록업체에서 DNS 설정:
- 서브도메인(`api.`) → **CNAME** → `yogob-api.fly.dev`
- 루트 도메인 → Fly가 주는 **A/AAAA** 레코드

전파되면 인증서가 자동 발급되고 커스텀 도메인으로 접속된다. `*.fly.dev` URL도 계속 살아있다.

---

## 8. 운영 메모

- **비밀값**: `POSTGRES_*`·`JWT_SECRET`(인증 구현 후)·`SMARTCHOICE_API_KEY`·`AI_INTERNAL_TOKEN`·
  `AI_SERVER_URL`은 `fly secrets`로만 넣는다.
  코드·`fly.toml`·git에 적지 않는다(`.dockerignore`가 `.env`를 차단).
- **비용 절감**: `auto_stop_machines="stop"` 은 그대로지만 BE 는 `min_machines_running=1` 이라 항상 한 대가 떠 있다.
  shared-cpu-1x·512MB 한 대 상시 가동 비용(월 2달러 수준)을 내고 위의 콜드 스타트 실패를 없앤 것이다.
  프론트(`yogob`)·AI(`yogob-ai`)는 기동이 빨라 `0` 을 유지한다.
- **마이그레이션**: 적용된 Flyway 파일은 수정 금지, 항상 새 `V{n}` 추가(`AGENTS.md`).
- **롤백**: `fly releases --app yogob-api` → `fly deploy --image <이전 이미지>` 또는 `fly apps restart`.

---

## 9. 지표 보기 (D-25)

배포하면 별도 설정 없이 쌓인다. 대시보드는 Fly 관리형 그라파나를 그대로 쓴다 — 우리가 만든 화면은 없다.

```bash
fly logs -a yogob-api                 # 로그
open https://fly-metrics.net          # 그라파나(조직 계정으로 로그인)
```

`fly.toml`의 `[metrics]`가 **9091/`/actuator/prometheus`**를 사설망에서 긁어간다.
공개 포트(8080)에는 액추에이터가 없다 — 열면 회원 수·내부 경로가 인증 없이 나간다.

| 보고 싶은 것 | 쿼리 |
|---|---|
| 엔드포인트별 p95 지연 | `histogram_quantile(0.95, sum by (le,uri) (rate(http_server_requests_seconds_bucket[5m])))` |
| 5xx 발생률 | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))` |
| 총 회원 / 24시간 가입 | `yogobi_members` / `yogobi_members_signed_up_24h` |
| 가입→구독 등록 전환율 | `yogobi_members_with_subscription / yogobi_members` |
| 활성 세션 | `yogobi_sessions_active` |

**화면 단위 이탈율·퍼널은 여기 없다.** 브라우저 이벤트가 서버에 오지 않아 DB에 기록 자체가 없다(D-25).

지표를 추가하려면 `common/Metrics.java`에 게이지 한 줄. 매 스크레이프(15초)마다 쿼리가 나가므로
집계 비용이 큰 값은 넣지 않는다.
