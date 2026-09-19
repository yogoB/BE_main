# DB ERD

> **마이그레이션에서 재구성해 손으로 묶었다.** 원본은 `db/migration/V*.sql` 이고, 이 문서와 어긋나면
> 마이그레이션이 옳다. 최종 확인 **2026-09-20 · V1~V29 · 테이블 29개**.
>
> 제약(CHECK·UNIQUE)과 인덱스는 싣지 않았다 — 그림이 읽히지 않는다. 필요하면 마이그레이션을 본다.

**읽는 순서**: 카탈로그(금액이 어디서 오나) → 회원·인증 → 회원 데이터 → 운영·수집 → 지표.

| 이 스키마가 지키는 약속 | |
|---|---|
| 금액 | `BIGINT` **원 단위**. `double`/`float` 를 쓰지 않는다 |
| 구독 등급 가격 | **전부 월 단가다.** 연간 총액이 한 줄 섞이면 그 사용자의 월 총액이 12배가 된다(G-54) |
| 카탈로그 삭제 | 지우지 않고 `active=false` 로 내린다 — 이미 고른 회원의 참조가 끊기면 안 된다(G-53) |
| 회원 탈퇴 | 회원 데이터는 `ON DELETE CASCADE` 로 함께 사라진다(D-11) |
| 출처 | 카탈로그 행은 `source_url`·`collected_at` 을 들고 다닌다(절대 원칙 4) |


## 카탈로그 — 금액의 원천

합본 CSV(`db/seed/catalog_combined.csv`)가 원본이고 이 표들은 그 투영이다(D-18). 요청 중 외부 가격 조회는 하지 않는다(D-05).

```mermaid
erDiagram
    carrier {
        bigint id PK
        text name
        text carrier_type
    }
    mobile_plan {
        bigint id PK
        bigint carrier_id FK
        text name
        text network_type
        bigint base_price
        bigint data_mb
        bigint voice_min
        bigint sms_cnt
        bigint contract_discount_12m
        bigint contract_discount_24m
        text age_limit
        text source_url
        date collected_at
        boolean active
    }
    subscription_service {
        bigint id PK
        text name
        text category
        text official_url
        boolean active
    }
    subscription_tier {
        bigint id PK
        bigint service_id FK
        text name
        bigint price
        integer concurrent_streams
        text quality
        text note
        boolean active
        text currency
        boolean tax_included
    }
    bundle_product {
        bigint id PK
        text name
        bigint price
        text provider
        boolean active
    }
    bundle_item {
        bigint bundle_id FK
        bigint tier_id FK
    }
    plan_benefit {
        bigint id PK
        bigint mobile_plan_id FK
        bigint service_id FK
        bigint tier_id
        text benefit_type
        numeric discount_value
        boolean is_exclusive
        text exclusive_group
        date valid_from
        date valid_to
        text source_url
        date collected_at
    }
    merchant_alias {
        bigint id PK
        bigint service_id FK
        text pattern
        text match_type
    }
    carrier ||--o{ mobile_plan : "carrier_id"
    subscription_service ||--o{ subscription_tier : "service_id"
    bundle_product ||--o{ bundle_item : "bundle_id"
    subscription_tier ||--o{ bundle_item : "tier_id"
    mobile_plan ||--o{ plan_benefit : "mobile_plan_id"
    subscription_service ||--o{ plan_benefit : "service_id"
    subscription_service ||--o{ merchant_alias : "service_id"
```


## 회원·인증

로그인은 Google 하나뿐이다(D-34). 세션은 절대 24시간·유휴 2시간이고 DB 행이 곧 세션이다(D-48).

```mermaid
erDiagram
    app_user {
        bigint id PK
        text email
        text password_hash
        bigint current_plan_id FK
        timestamptz created_at
        varchar google_sub
        boolean email_verified
        bigint credential_version
        varchar name
        varchar nickname
        varchar recovery_code_hash
    }
    auth_session {
        char token_hash
        bigint user_id FK
        char binding_hash
        timestamptz expires_at
        uuid id PK
        timestamptz created_at
        timestamptz last_seen_at
        varchar user_agent
    }
    auth_rate_limit {
        char bucket PK
        timestamptz expires_at
        integer attempts
    }
    user_consent {
        bigint id PK
        bigint user_id FK
        varchar item
        text policy_version
        timestamptz agreed_at
        timestamptz withdrawn_at
    }
    app_user ||--o{ auth_session : "user_id"
    app_user ||--o{ user_consent : "user_id"
```


## 회원 데이터

탈퇴하면 회원 행과 함께 사라진다(D-11). 법정 보존 의무가 확인된 증빙만 `retained_payment_record` 로 분리한다.

```mermaid
erDiagram
    user_subscription {
        bigint id PK
        bigint user_id FK
        bigint tier_id FK
        bigint monthly_price
        date started_at
        date ended_at
        date last_used_at
    }
    payment_record {
        bigint id PK
        bigint user_id FK
        text merchant_raw
        bigint service_id FK
        bigint amount
        date paid_at
        text source
    }
    retained_payment_record {
        bigint payment_record_id
        text merchant_raw
        bigint service_id
        bigint amount
        date paid_at
        text source
        text legal_basis
        date retention_start
        date retain_until
        timestamptz archived_at
    }
    detection_result {
        bigint id PK
        bigint user_id FK
        text rule_code
        text target_ref
        bigint wasted_amount
        timestamptz detected_at
        text provenance
    }
    saved_result {
        uuid id PK
        bigint user_id FK
        jsonb request
        jsonb cost
        timestamptz saved_at
        bigint monthly_savings_vs_current
    }
    member_savings {
        bigint user_id FK
        bigint monthly_savings
        timestamptz seen_at
    }
```


## 운영·수집

외부 조회는 배치와 승인 절차에서만 일어난다(D-05·D-20). 수집 결과는 **제안**이 되고 사람이 승인한다(D-28).

```mermaid
erDiagram
    catalog_change_request {
        bigint id PK
        bigint proposer_id
        text action
        text dataset
        text row_key
        text payload
        text reason
        text status
        bigint decided_by
        timestamptz decided_at
        text decision_note
        timestamptz created_at
        text review_status
    }
    catalog_audit {
        bigint id PK
        bigint actor_id
        text action
        text dataset
        text row_key
        text before_row
        text after_row
        text outcome
        text detail
        timestamptz created_at
    }
    catalog_candidate {
        bigint id PK
        text kind
        text query_text
        text status
        int requested_cnt
        timestamptz last_requested_at
        varchar note
    }
    catalog_report {
        uuid id PK
        text target_type
        bigint target_id
        text field
        varchar description
        varchar source_url
        text status
        timestamptz created_at
        varchar note
    }
    service_report {
        uuid id PK
        text category
        varchar description
        varchar page_url
        varchar source_url
        text status
        timestamptz created_at
        bigint user_id
        varchar note
    }
    smartchoice_plan_snapshot {
        bigint id PK
        text carrier
        text plan_name
        text network_type
        integer contract_months
        bigint plan_price
        bigint discounted_price
        text display_data
        text source
        text source_url
        timestamptz collected_at
    }
    fx_rate {
        text base
        text quote
        numeric rate
        date rate_date
        text source_url
        timestamptz fetched_at
    }
```


## 지표·감사

**사람 수와 횟수를 나눠 센다**(D-52) — 무한 호출 사고가 나도 사람 수는 안 부푼다. 운영자가 한 일은 `admin_action` 에 남는다.

```mermaid
erDiagram
    funnel_daily {
        date day PK
        text kind PK
        bigint count
    }
    funnel_event {
        date day PK
        text kind PK
        text actor_key PK
    }
    recommendation_daily {
        date day PK
        bigint plan_id PK
        int data_gb
        int count
    }
    admin_action {
        bigint id PK
        bigint actor_id
        text action
        text target
        text detail
        timestamptz created_at
    }
```


## 영역을 가로지르는 참조

| 참조하는 쪽 | 참조되는 쪽 |
|---|---|
| `app_user.current_plan_id` | `mobile_plan` |
| `user_subscription.user_id` | `app_user` |
| `user_subscription.tier_id` | `subscription_tier` |
| `payment_record.user_id` | `app_user` |
| `payment_record.service_id` | `subscription_service` |
| `detection_result.user_id` | `app_user` |
| `saved_result.user_id` | `app_user` |
| `member_savings.user_id` | `app_user` |
