# 시드 데이터

카탈로그 원본은 **`catalog_combined.csv` 한 파일**이다. 데이터셋별 CSV 를 따로 두지 않는다 —
같은 표를 두 파일로 두면 반드시 어긋난다(실제로 합본이 개별 시드보다 낡은 적이 있다).

| 파일 | 무엇 |
|---|---|
| `catalog_combined.csv` | 원본. `#@ <dataset>` 섹션 5개 — mobile_plan · subscription_service · subscription_tier · plan_benefit · bundle_product |
| `mobile_plan_TEMPLATE.csv` · `plan_benefit_TEMPLATE.csv` | 팀원 수집용 양식(수집 규칙은 `docs/data.md` §7) |
| `dev/` | 로컬 dev 프로파일 전용 더미. 커밋하지 않는다 |

**`source_url` 없는 행은 병합하지 않는다.** 수집 원자료와 변환 명령은 `../../sources/README.md`.

## 형식

`#@ <dataset>` 줄이 섹션 경계, 그 다음 비주석 줄이 CSV 헤더, 이후가 데이터 행이다.
그 밖의 `#` 줄은 주석이며 왕복(파싱→직렬화) 시 보존하지 않는다. 줄바꿈은 LF.
파서는 `CombinedCatalogCsv`(자바)와 `scripts/csv_sections.py`(파이썬) 두 곳이며 규칙은 하나다.

## 로딩

앱 시작 시 `CatalogSeedLoader`가 이 파일에서 섹션을 떼어 서비스 → 티어 → 번들 → 요금제 → 혜택 순으로 읽는다
(혜택은 요금제를, 번들은 티어를 참조하므로 순서를 바꾸지 않는다).
PostgreSQL [`COPY ... HEADER MATCH`](https://www.postgresql.org/docs/17/sql-copy.html)를 쓰므로
UTF-8, 헤더 이름·순서를 유지한다. `tier_ids`의 쉼표 목록은 따옴표로 감싼다.
빈 선택 필드는 `NULL`로 저장하며, 번들 구성은 `bundle_item`으로 나눈다.

`CATALOG_CSV_DIR`(운영자 검수·해시 잠금 모드)나 `yogobi.catalog.combined-csv`(외부 합본 경로)가
설정돼 있으면 내장 시드는 적재하지 않는다 — 운영 데이터를 덮어쓰지 않기 위해서다.
