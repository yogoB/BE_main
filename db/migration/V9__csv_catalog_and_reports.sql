-- V7은 기존 설치의 Flyway 이력으로 보존하고, 사용하지 않는 시세 테이블만 여기서 제거한다.
DROP TABLE smartchoice_plan_snapshot;

-- CSV에서 제외된 항목도 기존 회원 FK와 과거 입력을 보존한다.
ALTER TABLE mobile_plan ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE subscription_service ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE subscription_tier ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE bundle_product ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE catalog_report (
    id UUID PRIMARY KEY,
    target_type TEXT NOT NULL CHECK (target_type IN ('MOBILE_PLAN','SUBSCRIPTION_SERVICE','SUBSCRIPTION_TIER','BUNDLE_PRODUCT')),
    target_id BIGINT NOT NULL CHECK (target_id > 0),
    field TEXT NOT NULL CHECK (field IN ('PRICE','DATA','BENEFIT','AVAILABILITY','OTHER')),
    description VARCHAR(2000) NOT NULL CHECK (btrim(description) <> ''),
    source_url VARCHAR(2000),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RESOLVED','REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_catalog_report_created ON catalog_report(created_at);
