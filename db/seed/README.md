# 시드 데이터

수집 규칙은 `docs/data.md` §7. **`source_url` 없는 행은 병합하지 않는다.**

| 파일 | 상태 | 출처 |
|---|---|---|
| `subscription_service.csv` | ✅ 6건 | 스마트초이스(KTOA) 2026-09-07 |
| `subscription_tier.csv` | ✅ 17건 | 동일 |
| `bundle_product.csv` | ✅ 7건 | 동일 |
| `plan_benefit.csv` | ⬜ | D3 크롤링 |
| `mobile_plan.csv` | ⬜ | 팀원 수집 — TEMPLATE 참고 |
| `merchant_alias.csv` | ⬜ | 수기 |

## 로딩

앱 시작 시 `CatalogSeedLoader`가 확보된 CSV 3종을 서비스 → 티어 → 번들 순으로 읽는다.
PostgreSQL [`COPY ... HEADER MATCH`](https://www.postgresql.org/docs/17/sql-copy.html)를 사용하므로
UTF-8, 헤더 이름·순서를 유지한다. `tier_ids`의 쉼표 목록은 기존처럼 따옴표로 감싼다.
빈 선택 필드는 `NULL`로 저장하며, 번들 구성은 `bundle_item`으로 나눈다.

하나의 트랜잭션에서 ID 기준으로 추가·갱신하고, 잘못된 헤더·음수 가격·없는 티어 참조는
전체 로딩을 취소하며 앱 기동도 실패시킨다. 재실행 시 중복되지 않는다.
파일에서 빠진 기존 마스터 행은 삭제하지 않는다. 번들 구성만 해당 번들의 CSV 내용으로 교체한다.

`mobile_plan_TEMPLATE.csv`는 예시이며 로딩·JAR 패키징 대상에서 제외한다.
통신 요금제·혜택·가맹점 별칭은 실제 CSV 확보 시 로더에 추가한다.
